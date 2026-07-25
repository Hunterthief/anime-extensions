package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.util.Base64
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AllAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AllAnime"

    companion object {
        private const val STREAM_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"
        private const val API_URL = "https://api.allanime.day"
        private const val SITE_URL = "https://allmanga.to"
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val FALLBACK_PLAYER_DOMAIN = "https://blog.allanime.day"

        private const val DECRYPT_SECRET = "Xot36i3lK3"
        private const val DECRYPT_TAG_LENGTH = 128
        private const val DECRYPT_KEY_ALGO = "SHA-256"
        private const val DECRYPT_KEY_TYPE = "AES"
        private const val DECRYPT_CIPHER_ALGO = "AES/GCM/NoPadding"

        private val XOR_KEYS = arrayOf(
            "allanimenews",
            "1234567890123456789",
            "1234567890123456789012345",
            "s5feqxw21",
            "feqx1",
        )

        private val XOR_MASKS = XOR_KEYS.map { key ->
            key.fold(0) { mask, ch -> mask xor ch.code }
        }.toIntArray()

        private val INTERNAL_HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls",
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private val apiHeaders by lazy {
        Headers.Builder().apply {
            add("Accept", "*/*")
            add("Accept-Language", "en-US,en;q=0.9")
            add("Content-Type", "application/json")
            add("Origin", GRAPHQL_ORIGIN)
            add("Referer", "$GRAPHQL_ORIGIN/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#")  -> substring(1) to 0
            else -> this to null
        }

        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this
        }

        if (keyType == null) {
            for (mask in XOR_MASKS) {
                val decrypted = String(CharArray(parsedChunks.size) { i ->
                    ((parsedChunks[i] xor mask) and 0xFF).toChar()
                })
                if (decrypted.contains("/clock") || decrypted.contains("http")) {
                    return decrypted
                }
            }
            return this
        }

        val mask = XOR_MASKS[keyType]
        return String(CharArray(parsedChunks.size) { i ->
            ((parsedChunks[i] xor mask) and 0xFF).toChar()
        })
    }

    private fun decryptTobeparsed(base64Payload: String): String {
        return try {
            val blob = Base64.decode(base64Payload, Base64.DEFAULT)
            if (blob.size < 13) return ""

            val versionByte = blob[0].toInt() and 0xFF
            val iv = blob.sliceArray(1 until 13)
            val encryptedData = blob.sliceArray(13 until blob.size)

            val keyBytes = MessageDigest.getInstance(DECRYPT_KEY_ALGO)
                .digest("$DECRYPT_SECRET:v$versionByte".toByteArray(Charsets.UTF_8))

            val cipher = Cipher.getInstance(DECRYPT_CIPHER_ALGO)
            val gcmSpec = GCMParameterSpec(DECRYPT_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, DECRYPT_KEY_TYPE), gcmSpec)

            String(cipher.doFinal(encryptedData), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun fetchShowId(title: String): String = withContext(Dispatchers.IO) {
        val query = """
            query(${'$'}search: SearchInput, ${'$'}limit: Int, ${'$'}page: Int, ${'$'}translationType: VaildTranslationTypeEnumType, ${'$'}countryOrigin: VaildCountryOriginEnumType) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page, translationType: ${'$'}translationType, countryOrigin: ${'$'}countryOrigin) {
                edges { _id name }
              }
            }
        """.trimIndent()

        val payload = buildJsonObject {
            put("query", query)
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                }
                put("limit", 40)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
        }

        val res = makePostRequest(payload) ?: return@withContext ""
        if (res.startsWith("ERR") || res.startsWith("EXC")) return@withContext ""

        try {
            res.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?.id ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun makePostRequest(payload: kotlinx.serialization.json.JsonObject): String? {
        return try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$API_URL/api")
                .post(body)
                .headers(apiHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) "ERR:${res.code}"
                else bodyStr
            }
        } catch (_: Exception) {
            "EXC"
        }
    }

    private fun fetchIframeEndpoint(): String {
        return try {
            val request = Request.Builder()
                .url("$SITE_URL/getVersion")
                .headers(apiHeaders)
                .get()
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return FALLBACK_PLAYER_DOMAIN
                res.body.string().parseAs<AllAnimeVersionResponse>().episodeIframeHead
                    ?: FALLBACK_PLAYER_DOMAIN
            }
        } catch (_: Exception) {
            FALLBACK_PLAYER_DOMAIN
        }
    }

    private fun extractInternalHoster(url: String, sourceName: String, endPoint: String): List<Video> {
        return try {
            val clockUrl = endPoint + url.replace("/clock?", "/clock.json?")

            val request = Request.Builder()
                .url(clockUrl)
                .headers(apiHeaders)
                .get()
                .build()

            val linkJson = client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                res.body.string().parseAs<AllAnimeVideoLink>()
            }

            linkJson.links.flatMap { link ->
                val subtitles = link.subtitles?.map { sub ->
                    val label = sub.label?.let { " - $it" } ?: ""
                    Track(sub.src, Locale(sub.lang).displayLanguage + label)
                }.orEmpty()

                when {
                    link.mp4 == true -> {
                        listOf(Video(
                            link.link,
                            "Original ($name - ${link.resolutionStr})",
                            link.link,
                            subtitleTracks = subtitles,
                        ))
                    }
                    link.hls == true -> {
                        val masterHeaders = apiHeaders.newBuilder()
                            .set("Host", link.link.toHttpUrl().host)
                            .set("Origin", endPoint)
                            .set("Referer", "$endPoint/")
                            .build()

                        playlistUtils.extractFromHls(
                            link.link,
                            masterHeaders = masterHeaders,
                            videoHeaders = masterHeaders,
                            videoNameGen = { quality -> "$quality ($name - ${link.resolutionStr})" },
                            subtitleList = subtitles,
                        )
                    }
                    link.crIframe == true -> {
                        link.portData?.streams?.flatMap { stream ->
                            when (stream.format) {
                                "adaptive_dash" -> listOf(Video(
                                    stream.url,
                                    "Original (AC - Dash${if (stream.hardsub_lang.isEmpty()) "" else " - Hardsub: ${stream.hardsub_lang}"})",
                                    stream.url,
                                    subtitleTracks = subtitles,
                                ))
                                "adaptive_hls" -> playlistUtils.extractFromHls(
                                    stream.url,
                                    masterHeaders = apiHeaders,
                                    videoHeaders = apiHeaders,
                                    videoNameGen = { quality ->
                                        "$quality (AC - HLS${if (stream.hardsub_lang.isEmpty()) "" else " - Hardsub: ${stream.hardsub_lang}"})"
                                    },
                                    subtitleList = subtitles,
                                )
                                else -> emptyList()
                            }
                        }.orEmpty()
                    }
                    link.dash == true -> {
                        val audioList = link.rawUrls?.audios?.map {
                            Track(it.url, "${it.bandwidth / 1000} kb/s")
                        }.orEmpty()

                        link.rawUrls?.vids?.map { vid ->
                            Video(
                                vid.url,
                                "$name - ${vid.height}p ${vid.bandwidth / 1000} kb/s",
                                vid.url,
                                audioTracks = audioList,
                                subtitleTracks = subtitles,
                            )
                        }.orEmpty()
                    }
                    else -> emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val showId = fetchShowId(title)
        if (showId.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val variablesJson = buildJsonObject {
                    put("showId", showId)
                    put("translationType", "sub")
                    put("episodeString", meta.epNum.toString())
                }.toString()

                val extensionsJson = buildJsonObject {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", STREAM_HASH)
                    }
                }.toString()

                val url = "$API_URL/api".toHttpUrl().newBuilder()
                    .addQueryParameter("variables", variablesJson)
                    .addQueryParameter("extensions", extensionsJson)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .headers(apiHeaders)
                    .get()
                    .build()

                val responseBody = client.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) return@withContext emptyList<Video>()
                    res.body.string()
                }

                val parsed = responseBody.parseAs<AllAnimeResponse>()
                val tobeparsed = parsed.data?.tobeparsed

                val sourceUrls = if (!tobeparsed.isNullOrBlank()) {
                    val decryptedJson = decryptTobeparsed(tobeparsed)
                    if (decryptedJson.isBlank()) return@withContext emptyList<Video>()
                    decryptedJson.parseAs<DecryptedEpisodeResult>().episode?.sourceUrls ?: emptyList()
                } else {
                    parsed.data?.episode?.sourceUrls ?: emptyList()
                }

                if (sourceUrls.isEmpty()) return@withContext emptyList<Video>()

                val iframeEndpoint = fetchIframeEndpoint()
                val videos = mutableListOf<Video>()

                for (source in sourceUrls) {
                    val decryptedUrl = source.sourceUrl.decryptSource()

                    when {
                        decryptedUrl.startsWith("/apivtwo/") || decryptedUrl.contains("/clock") -> {
                            videos.addAll(extractInternalHoster(decryptedUrl, source.sourceName, iframeEndpoint))
                        }
                        decryptedUrl.contains(".m3u8") -> {
                            try {
                                videos.addAll(
                                    playlistUtils.extractFromHls(
                                        decryptedUrl, decryptedUrl, apiHeaders, apiHeaders
                                    )
                                )
                            } catch (_: Exception) {
                                videos.add(Video(decryptedUrl, "$name HLS", decryptedUrl, headers = apiHeaders))
                            }
                        }
                        decryptedUrl.contains("filemoon") || decryptedUrl.contains("moonplayer") -> {
                            videos.addAll(filemoonExtractor.videosFromUrl(decryptedUrl, prefix = "$name Filemoon:"))
                        }
                        decryptedUrl.contains("streamwish") || decryptedUrl.contains("wish") || decryptedUrl.contains("swhoi") -> {
                            videos.addAll(streamwishExtractor.videosFromUrl(decryptedUrl, videoNameGen = { "$name StreamWish:$it" }))
                        }
                        decryptedUrl.contains("mp4upload") -> {
                            videos.addAll(mp4uploadExtractor.videosFromUrl(decryptedUrl, apiHeaders))
                        }
                        decryptedUrl.contains("dood") -> {
                            videos.addAll(doodExtractor.videosFromUrl(decryptedUrl))
                        }
                        decryptedUrl.contains("vidstreaming") || decryptedUrl.contains("gogo") ||
                        decryptedUrl.contains("vidcloud") || decryptedUrl.contains("playgo1") ||
                        decryptedUrl.contains("playtaku") -> {
                            videos.addAll(gogoStreamExtractor.videosFromUrl(
                                decryptedUrl.replace(Regex("^//"), "https://")
                            ))
                        }
                        decryptedUrl.contains("streamlare") -> {
                            videos.addAll(streamlareExtractor.videosFromUrl(decryptedUrl))
                        }
                        decryptedUrl.contains("ok.ru") || decryptedUrl.contains("okru") -> {
                            videos.addAll(okruExtractor.videosFromUrl(decryptedUrl))
                        }
                        else -> {
                            if (decryptedUrl.startsWith("http")) {
                                videos.add(Video(decryptedUrl, "$name ${source.sourceName}", decryptedUrl, headers = apiHeaders))
                            }
                        }
                    }
                }
                videos
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
