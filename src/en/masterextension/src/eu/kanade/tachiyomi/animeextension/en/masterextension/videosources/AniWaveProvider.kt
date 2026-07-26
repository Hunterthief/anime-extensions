package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniWaveProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniWave"

    companion object {
        private val DOMAINS = listOf(
            "https://aniwave.to",
            "https://aniwave.cz",
        )

        private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
        private const val KEY_1 = "ItFKjuWokn4ZpB"
        private const val KEY_2 = "fOyt97QWFB3"
        private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
        private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
        private const val KEY_3 = "736y1uTJpBLUX"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    @Serializable
    private data class ResultResponse(val result: String)

    @Serializable
    private data class ServerResponseDto(val result: ServerResultDto) {
        @Serializable
        data class ServerResultDto(val url: String)
    }

    @Serializable
    private data class SourceResponseDto(
        @Serializable(with = SourcesSerializer::class) val sources: String,
        val tracks: List<TrackDto>? = null,
    ) {
        @Serializable
        data class TrackDto(val file: String, val kind: String, val label: String = "")
    }

    private object SourcesSerializer : KSerializer<String> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun deserialize(decoder: Decoder): String {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            return when (element) {
                is JsonObject -> element["file"]?.jsonPrimitive?.content
                is JsonArray -> element.firstOrNull()?.let {
                    when (it) {
                        is JsonObject -> it["file"]?.jsonPrimitive?.content
                        is JsonPrimitive -> it.content
                        else -> null
                    }
                }
                is JsonPrimitive -> element.content
                else -> null
            } ?: throw IllegalStateException("No valid m3u8 in sources")
        }
        override fun serialize(encoder: Encoder, value: String) =
            throw UnsupportedOperationException()
    }

    // FIX: Add User-Agent and remove AniList Origin/Accept headers
    private fun siteHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .removeAll("Origin")
        .build()

    private fun ajaxHeaders(baseUrl: String, refererPath: String) = headers.newBuilder()
        .set("Accept", "application/json, text/javascript, */*; q=0.01")
        .set("Referer", "$baseUrl$refererPath")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .removeAll("Origin")
        .build()

    private fun vrfEncrypt(input: String): String {
        var vrf = input
        vrf = exchange(vrf, EXCHANGE_KEY_1)
        vrf = rc4Encrypt(KEY_1, vrf)
        vrf = rc4Encrypt(KEY_2, vrf)
        vrf = exchange(vrf, EXCHANGE_KEY_2)
        vrf = exchange(vrf, EXCHANGE_KEY_3)
        vrf = vrf.reversed()
        vrf = rc4Encrypt(KEY_3, vrf)
        vrf = Base64.encode(vrf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
        return URLEncoder.encode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.parameters)
        val output = cipher.doFinal(input.toByteArray())
        return Base64.encode(output, Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, keys: List<String>): String {
        val key1 = keys[0]
        val key2 = keys[1]
        return input.map { c ->
            val idx = key1.indexOf(c)
            if (idx != -1) key2[idx] else c
        }.joinToString("")
    }

    private suspend fun searchAnime(
        title: String,
    ): Triple<String, String, String> {
        var lastError: Throwable? = null
        val encodedTitle = URLEncoder.encode(title, "utf-8")
        
        for (domain in DOMAINS) {
            try {
                val vrf = vrfEncrypt(title)
                // FIX: Manually build URL to prevent OkHttp from double-encoding the VRF token
                val url = "$domain/filter?keyword=$encodedTitle&page=1&vrf=$vrf"

                val html = client.newCall(GET(url, siteHeaders(domain)))
                    .awaitSuccess().bodyString()

                val doc = Jsoup.parse(html, domain)
                
                val item = doc.selectFirst("div.ani.items > div.item a.name")
                    ?: doc.selectFirst("div.item a[href*=/watch/]")
                    ?: doc.selectFirst("a[href*=/watch/]")

                if (item != null) {
                    val href = item.attr("abs:href").ifBlank { item.attr("href") }
                        .substringBefore("?").replace(Regex("/ep-\\d+$"), "")
                        
                    if (href.isNotBlank()) {
                        var path = if (href.startsWith("http")) href.substringAfter(domain) else href
                        if (!path.startsWith("/")) path = "/$path"
                        
                        val animeHtml = client.newCall(GET("$domain$path", siteHeaders(domain)))
                            .awaitSuccess().bodyString()
                        val animeDoc = Jsoup.parse(animeHtml, domain)
                        val dataId = animeDoc.selectFirst("[data-id]")?.attr("data-id")
                            ?: animeDoc.selectFirst("[data-tip]")?.attr("data-tip")
                            ?: ""
                        if (dataId.isNotBlank()) {
                            return Triple(domain, path, dataId)
                        }
                        lastError = Exception("No data-id found at $path")
                    } else {
                        lastError = Exception("Empty href")
                    }
                } else {
                    lastError = Exception("No results in HTML")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Search returned null")
    }

    private suspend fun getEpisodeServerIds(
        baseUrl: String,
        animePath: String,
        animeId: String,
        epNum: Int,
    ): String? {
        val vrf = vrfEncrypt(animeId)
        val url = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf"

        val body = client.newCall(GET(url, ajaxHeaders(baseUrl, animePath)))
            .awaitSuccess().bodyString()

        val result = body.parseAs<ResultResponse>()
        val doc = Jsoup.parseBodyFragment(result.result)

        val epElement = doc.select("div.episodes ul > li > a").firstOrNull {
            it.attr("data-num") == epNum.toString()
        } ?: doc.select("a[data-num]").firstOrNull {
            it.attr("data-num") == epNum.toString()
        }

        return epElement?.attr("data-ids")?.takeIf { it.isNotBlank() }
    }

    private data class ServerData(
        val type: String,
        val serverId: String,
        val serverName: String,
    )

    private suspend fun getServerList(
        baseUrl: String,
        serverIds: String,
        epUrl: String,
    ): List<ServerData> {
        val url = "$baseUrl/ajax/server/list?servers=$serverIds"

        val body = client.newCall(GET(url, ajaxHeaders(baseUrl, epUrl)))
            .awaitSuccess().bodyString()

        val result = body.parseAs<ResultResponse>()
        val doc = Jsoup.parseBodyFragment(result.result)

        return doc.select("div.type").flatMap { typeElem ->
            val label = typeElem.selectFirst("label")?.text()?.trim()
                ?: typeElem.attr("data-type").trim().ifBlank { "Sub" }

            typeElem.select("li").mapNotNull { li ->
                if (li.hasClass("download-icon")) return@mapNotNull null
                val id = li.attr("data-link-id")
                val name = li.text().trim()
                if (id.isNotBlank() && name.isNotBlank()) {
                    ServerData(label, id, name)
                } else null
            }
        }
    }

    private suspend fun getEmbedUrl(
        baseUrl: String,
        serverId: String,
        epUrl: String,
    ): String {
        val url = "$baseUrl/ajax/server?get=$serverId"
        val body = client.newCall(GET(url, ajaxHeaders(baseUrl, epUrl)))
            .awaitSuccess().bodyString()
        return body.parseAs<ServerResponseDto>().result.url
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        server: ServerData,
        baseUrl: String,
    ): List<Video> {
        if (embedUrl.contains(".m3u8") && !embedUrl.contains("/stream/")) {
            return extractHls(embedUrl, server, embedUrl.substringBeforeLast("/"))
        }

        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            return emptyList()
        }

        val pageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .removeAll("Origin")
            .build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders))
            .awaitSuccess().bodyString()

        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchFromGetSources(dataId, host, embedUrl, server)
        }

        val iframeSrc = Regex("""<iframe[^>]+src="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolved = resolveUrl(iframeSrc, embedUrl)
            return extractFromEmbed(resolved, server, baseUrl)
        }

        val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageBody)
        if (m3u8Match != null) {
            return extractHls(m3u8Match.value, server, "https://$host/")
        }

        val sourceSrc = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
            .find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            return extractHls(resolveUrl(sourceSrc, embedUrl), server, "https://$host/")
        }

        val jsVar = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*\.m3u8[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']""",
        ).find(pageBody)
        if (jsVar != null) {
            val url = jsVar.groupValues[1].ifBlank { jsVar.groupValues[2] }
            if (url.isNotBlank()) {
                return extractHls(resolveUrl(url, embedUrl), server, "https://$host/")
            }
        }

        return emptyList()
    }

    private suspend fun fetchFromGetSources(
        dataId: String,
        host: String,
        embedUrl: String,
        server: ServerData,
    ): List<Video> {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .set("Origin", "https://$host")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .build()

        val sourceData = try {
            client.newCall(GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders))
                .awaitSuccess().parseAs<SourceResponseDto>()
        } catch (_: Exception) {
            client.newCall(GET("https://$host/stream/getSourcesNew?id=$dataId&id=$dataId", apiHeaders))
                .awaitSuccess().parseAs<SourceResponseDto>()
        }

        val m3u8 = sourceData.sources
        if (!m3u8.startsWith("http")) return emptyList()

        val subtitles = sourceData.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .build()

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { q -> "${server.serverName} - ${server.type} - $q" },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    private suspend fun extractHls(
        m3u8Url: String,
        server: ServerData,
        referer: String,
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .removeAll("Origin")
            .build()
        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { q -> "${server.serverName} - ${server.type} - $q" },
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    private fun resolveUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.toHttpUrl().resolve(url)?.toString() ?: url
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        val (baseUrl, animePath, animeId) = try {
            searchAnime(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        }

        val serverIds = try {
            getEpisodeServerIds(baseUrl, animePath, animeId, epNum)
        } catch (e: Exception) {
            return debugVideo("epList threw: ${e.message}")
        }
        if (serverIds == null) {
            return debugVideo("no epIds for '$title' ep$epNum (id=$animeId)")
        }

        val epUrl = "$animePath/ep-$epNum"
        val servers = try {
            getServerList(baseUrl, serverIds, epUrl)
        } catch (e: Exception) {
            return debugVideo("serverList threw: ${e.message}")
        }
        if (servers.isEmpty()) {
            return debugVideo("0 servers for '$title' ep$epNum")
        }

        val allVideos = mutableListOf<Video>()
        val errors = mutableListOf<String>()

        servers.forEach { server ->
            try {
                val embedUrl = getEmbedUrl(baseUrl, server.serverId, epUrl)
                val videos = extractFromEmbed(embedUrl, server, baseUrl)
                if (videos.isEmpty()) {
                    errors.add("empty:${server.serverName}")
                } else {
                    allVideos.addAll(videos)
                }
            } catch (e: Exception) {
                errors.add("err:${server.serverName}:${e.message?.take(30)}")
            }
        }

        if (allVideos.isEmpty()) {
            return debugVideo(
                "0 videos from ${servers.size} servers. ${errors.take(3).joinToString(" | ")}",
            )
        }

        return allVideos
    }
}
