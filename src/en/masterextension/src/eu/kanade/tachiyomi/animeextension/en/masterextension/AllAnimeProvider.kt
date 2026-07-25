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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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

        private val INTERAL_HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls",
        )

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
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    // Headers for POST requests (search, etc.) — matches reference buildPost()
    private fun buildPostHeaders(body: okhttp3.RequestBody): Headers {
        return headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", body.contentLength().toString())
            add("Content-Type", body.contentType().toString())
            add("Host", API_URL.toHttpUrl().host)
            add("Origin", GRAPHQL_ORIGIN)
            add("Referer", "$GRAPHQL_ORIGIN/")
        }.build()
    }

    // Headers for GET requests (video sources) — uses site referer like reference
    private val siteHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$SITE_URL/")
            .build()
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
            XOR_MASKS.forEach { mask ->
                val decrypted = String(CharArray(parsedChunks.size) { i ->
                    ((parsedChunks[i] xor mask) and 0xFF).toChar()
                })
                if (decrypted.contains("/clock") || decrypted.contains("http")) return decrypted
            }
            return this
        }

        val mask = XOR_MASKS[keyType]
        return String(CharArray(parsedChunks.size) { i ->
            ((parsedChunks[i] xor mask) and 0xFF).toChar()
        })
    }

    private fun decryptTobeparsed(base64Payload: String): String {
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

        return String(cipher.doFinal(encryptedData), Charsets.UTF_8)
    }

    private suspend fun fetchShowId(title: String): String {
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
                put("limit", 30)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
        }

        val body = payload.toJsonString().toJsonBody()
        val postHeaders = buildPostHeaders(body)

        val response = client.newCall(
            POST("$API_URL/api", headers = postHeaders, body = body)
        ).awaitSuccess().bodyString()

        return response.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?.id ?: ""
    }

    private fun buildVideoListRequest(showId: String, episodeString: String): Request {
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", "sub")
            put("episodeString", episodeString)
        }

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", STREAM_HASH)
            }
        }

        val url = API_URL.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables.toJsonString())
            addQueryParameter("extensions", extensions.toJsonString())
        }.build().toString()

        return GET(url, siteHeaders)
    }

    private suspend fun extractInternalHoster(
        url: String,
        sourceName: String,
        endPoint: String
    ): List<Video> {
        val linkJson = client.newCall(
            GET(endPoint + url.replace("/clock?", "/clock.json?"))
        ).awaitSuccess().parseAs<AllAnimeVideoLink>()

        return linkJson.links.parallelCatchingFlatMap { link ->
            val subtitles = link.subtitles?.map { sub ->
                val label = sub.label?.let { " - $it" } ?: ""
                Track(sub.src, Locale(sub.lang).displayLanguage + label)
            }.orEmpty()

            when {
                link.mp4 == true -> {
                    Video(
                        link.link,
                        "Original ($name - ${link.resolutionStr})",
                        link.link,
                        subtitleTracks = subtitles,
                    ).let(::listOf)
                }

                link.hls == true -> {
                    val masterHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", link.link.toHttpUrl().host)
                        .add("Origin", endPoint)
                        .add("Referer", "$endPoint/")
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
                    link.portData?.streams?.parallelCatchingFlatMap { stream ->
                        when (stream.format) {
                            "adaptive_dash" -> Video(
                                stream.url,
                                "Original (AC - Dash${if (stream.hardsub_lang.isEmpty()) "" else " - Hardsub: ${stream.hardsub_lang}"})",
                                stream.url,
                                subtitleTracks = subtitles,
                            ).let(::listOf)

                            "adaptive_hls" -> playlistUtils.extractFromHls(
                                stream.url,
                                masterHeaders = headers,
                                videoHeaders = headers,
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
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val showId = fetchShowId(title)
        if (showId.isBlank()) return emptyList()

        // Fetch episode source URLs — matches reference getVideoList exactly
        val responseBody = client.newCall(
            buildVideoListRequest(showId, meta.epNum.toString())
        ).awaitSuccess().bodyString()

        // Check for encrypted response
        val tobeparsed = runCatching {
            responseBody.parseAs<AllAnimeResponse>().data?.tobeparsed
        }.getOrNull()

        val sourceUrls = if (!tobeparsed.isNullOrBlank()) {
            decryptTobeparsed(tobeparsed).parseAs<DecryptedEpisodeResult>().episode?.sourceUrls
        } else {
            responseBody.parseAs<AllAnimeResponse>().data?.episode?.sourceUrls
        } ?: emptyList()

        if (sourceUrls.isEmpty()) return emptyList()

        // Build server list — matches reference filtering logic
        val mappings = listOf(
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc", "playtaku", "vidcloud"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru", "okru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
            "filemoon" to listOf("filemoon", "moonplayer"),
            "streamwish" to listOf("wish"),
        )

        data class Server(val sourceUrl: String, val sourceName: String, val priority: Float)

        val serverList = mutableListOf<Server>()
        sourceUrls.forEach { video ->
            val videoUrl = video.sourceUrl.decryptSource()

            val matchingMapping = mappings.firstOrNull { (_, urlMatches) ->
                urlMatches.any { videoUrl.contains(it) }
            }

            when {
                // Internal hoster: URL starts with /apivtwo/ AND source name matches
                videoUrl.startsWith("/apivtwo/") && INTERAL_HOSTER_NAMES.any {
                    Regex("""\b${it.lowercase()}\b""").find(video.sourceName.lowercase()) != null
                } -> Server(videoUrl, "internal ${video.sourceName}", video.priority ?: 0f)
                    .let(serverList::add)

                // Player type
                video.type == "player" ->
                    Server(videoUrl, "player@${video.sourceName}", video.priority ?: 0f)
                        .let(serverList::add)

                // Known alt hoster
                matchingMapping != null ->
                    Server(videoUrl, matchingMapping.first, video.priority ?: 0f)
                        .let(serverList::add)

                // Direct URL fallback
                videoUrl.startsWith("http") ->
                    Server(videoUrl, "direct@${video.sourceName}", video.priority ?: 0f)
                        .let(serverList::add)
            }
        }

        if (serverList.isEmpty()) return emptyList()

        // Fetch iframe endpoint
        val iframeEndpoint = runCatching {
            client.newCall(GET("$SITE_URL/getVersion")).awaitSuccess()
                .parseAs<AllAnimeVersionResponse>()
                .episodeIframeHead ?: FALLBACK_PLAYER_DOMAIN
        }.getOrDefault(FALLBACK_PLAYER_DOMAIN)

        // Extract videos from all servers in parallel — matches reference
        return serverList.parallelCatchingFlatMap { server ->
            val sName = server.sourceName
            when {
                sName.startsWith("internal ") -> {
                    extractInternalHoster(server.sourceUrl, server.sourceName, iframeEndpoint)
                }

                sName.startsWith("player@") -> {
                    val videoHeaders = headers.newBuilder().apply {
                        add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                        add("Host", server.sourceUrl.toHttpUrl().host)
                        add("Referer", "$iframeEndpoint/")
                    }.build()

                    Video(
                        server.sourceUrl,
                        "Original (player ${server.sourceName.substringAfter("player@")})",
                        server.sourceUrl,
                        headers = videoHeaders,
                    ).let(::listOf)
                }

                sName == "vidstreaming" -> {
                    gogoStreamExtractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))
                }

                sName == "doodstream" -> {
                    doodExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "okru" -> {
                    okruExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "mp4upload" -> {
                    mp4uploadExtractor.videosFromUrl(server.sourceUrl, headers)
                }

                sName == "streamlare" -> {
                    streamlareExtractor.videosFromUrl(server.sourceUrl)
                }

                sName == "filemoon" -> {
                    filemoonExtractor.videosFromUrl(server.sourceUrl, prefix = "Filemoon:")
                }

                sName == "streamwish" -> {
                    streamwishExtractor.videosFromUrl(server.sourceUrl, videoNameGen = { "StreamWish:$it" })
                }

                sName.startsWith("direct@") -> {
                    Video(
                        server.sourceUrl,
                        "$name ${server.sourceName.substringAfter("direct@")}",
                        server.sourceUrl,
                        headers = siteHeaders,
                    ).let(::listOf)
                }

                else -> emptyList()
            }
        }
    }
}
