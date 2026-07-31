package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

data class KwikContent(val cookies: String, val html: String, val finalUrl: String)
private data class HlsStream(val url: String, val referer: String)

class KwikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val cfBypassUserAgent: String? = null,
) {
    private val kwikParamsRegex by lazy { Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""") }
    private val kwikDUrl by lazy { Regex("action=\"([^\"]+)\"") }
    private val kwikDToken by lazy { Regex("value=\"([^\"]+)\"") }

    private val cookieFreeClient by lazy {
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val noRedirectClient by lazy {
        cookieFreeClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val kwikHeaders by lazy {
        headers.newBuilder()
            .set("Origin", "https://kwik.cx")
            .set("Referer", "https://kwik.cx/")
            .build()
    }

    suspend fun getHlsVideo(kwikUrl: String, referer: String, quality: String = ""): Video {
        val hlsStream = getHlsStream(kwikUrl, referer)

        return Video(
            hlsStream.url,
            quality,
            hlsStream.url,
            headers = kwikHeaders.newBuilder()
                .set("Referer", hlsStream.referer)
                .build(),
        )
    }

    private suspend fun getHlsStream(kwikUrl: String, referer: String): HlsStream =
        client.newCall(GET(kwikUrl, headers.newBuilder().set("Referer", referer).build()))
            .awaitSuccess().use { response ->
                val finalUrl = response.request.url.toString()
                val eContent = response.useAsJsoup()
                val script = eContent.selectFirst("script:containsData(eval\\(function)")?.data()
                    ?.substringAfterLast("eval(function(")
                    ?: throw KwikException.ExtractionException("JsUnpacker not found.")
                val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")
                    ?: throw KwikException.ExtractionException("JsUnpacker failed to unpack Kwik script.")
                HlsStream(
                    url = unpacked.substringAfter("const source=\\'").substringBefore("\\';"),
                    referer = finalUrl,
                )
            }

    suspend fun getStreamVideo(paheUrl: String, quality: String = ""): Video {
        val videoUrl = getStreamUrlFromKwik(paheUrl)

        return Video(
            videoUrl,
            quality,
            videoUrl,
            headers = kwikHeaders,
        )
    }

    suspend fun getStreamUrlFromKwik(paheUrl: String): String {
        val kwikUrl = noRedirectClient.newCall(GET("$paheUrl/i", headers)).await().use { response ->
            val location = response.header("location")
                ?: throw KwikException.ExtractionException("Pahe redirect failed: No location header found.")
            "https://" + location.substringAfterLast("https://")
        }

        var (fContentCookies, fContentString, fContentUrl) = fetchKwikHtml(kwikUrl)

        val match = kwikParamsRegex.find(fContentString)
            ?: throw KwikException.ExtractionException("Could not find decryption parameters in Kwik HTML.")

        val (fullString, key, v1, v2) = match.destructured
        val decrypted = decrypt(fullString, key, v1.toIntOrNull() ?: 0, v2.toIntOrNull() ?: 0)

        val uri = kwikDUrl.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream URI.")
        val tok = kwikDToken.find(decrypted)?.groupValues?.get(1)
            ?: throw KwikException.ExtractionException("Failed to decrypt stream Token.")

        var cloudFlareBypassResult: CloudFlareBypassResult? = null
        var kwikLocation: String? = null
        var code = 419
        var tries = 0
        val tryLimit = 2

        while (code != 302 && tries < tryLimit) {
            tries++
            val headersBuilder = kwikHeaders.newBuilder()
                .set("Referer", fContentUrl)
                .set("Cookie", fContentCookies)

            cloudFlareBypassResult?.let { headersBuilder.set("User-Agent", it.userAgent) }

            noRedirectClient.newCall(
                POST(uri, headersBuilder.build(), FormBody.Builder().add("_token", tok).build()),
            ).await().use { response ->
                code = response.code
                kwikLocation = response.header("location")
            }

            if ((code == 403 || code == 419) && tries < tryLimit) {
                cloudFlareBypassResult = CloudflareBypass().getCookies(kwikUrl, cfBypassUserAgent)
                    ?: throw KwikException.CloudflareBlockedException("Failed to bypass Kwik Cloudflare.")

                val cleanedCookies = fContentCookies.split("; ")
                    .filter { !it.trimStart().startsWith("cf_clearance=") }
                    .joinToString("; ")

                fContentCookies = "$cleanedCookies; ${cloudFlareBypassResult.cookies}"
            }
        }

        return kwikLocation ?: throw KwikException.ExtractionException("Failed to extract Kwik stream URI after $tries attempts.")
    }

    private suspend fun fetchKwikHtml(kwikUrl: String): KwikContent {
        suspend fun attemptKwikFetch(cfResult: CloudFlareBypassResult?): KwikContent? {
            val headers = Headers.Builder()
                .set("Origin", "https://kwik.cx")
                .set("Referer", "https://kwik.cx/")
                .apply {
                    if (cfResult != null) {
                        set("Cookie", cfResult.cookies)
                        set("User-Agent", cfResult.userAgent)
                    }
                }
                .build()

            return try {
                cookieFreeClient.newCall(GET(kwikUrl, headers)).awaitSuccess().use { resp ->
                    val html = resp.bodyString()
                    if (html.contains("eval(function(")) {
                        val respCookies = resp.extractCookies()
                        val finalCookies =
                            listOfNotNull(respCookies.ifBlank { null }, cfResult?.cookies?.ifBlank { null }).joinToString("; ")
                        KwikContent(finalCookies, html, resp.request.url.toString())
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        attemptKwikFetch(null)?.let { return it }

        val cfResult = CloudflareBypass().getCookies(kwikUrl, cfBypassUserAgent)
            ?: throw KwikException.CloudflareBlockedException("Failed to bypass Kwik Cloudflare.")

        attemptKwikFetch(cfResult)?.let { return it }

        throw KwikException.CloudflareBlockedException("Failed to bypass Kwik Cloudflare.")
    }

    private fun Response.extractCookies(): String = headers("set-cookie").joinToString("; ") { it.substringBefore(";") }

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            if (nextIndex == -1) break

            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1

            try {
                val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
                sb.append(decodedChar)
            } catch (_: NumberFormatException) {
                break
            }
        }

        return sb.toString()
    }

    sealed class KwikException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ExtractionException(message: String, cause: Throwable? = null) : KwikException(message, cause)
        class CloudflareBlockedException(message: String) : KwikException(message)
    }
}
