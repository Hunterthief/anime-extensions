package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.ANIME_LANE
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.EPISODES_QUERY
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaDecryptedResult
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaEncryptedResult
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaEpisodeResult
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaKeyManager
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaSearchResult
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaSeriesResult
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.MKissaSourceUrl
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.SEARCH_QUERY
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.STREAM_HASH
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa.STREAM_QUERY
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MKissaProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "MKissa"
    override val baseUrl = "https://mkissa.to"

    private val apiUrl = "https://api.mkissa.net"

    private val mkissaClient: OkHttpClient by lazy {
        client.newBuilder()
            .apply { networkInterceptors().clear() }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val mkissaHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/anime/")
            .build()
    }

    private val showIdCache = ConcurrentHashMap<Int, String>()

    private val keyManager by lazy {
        MKissaKeyManager(mkissaClient, mkissaHeaders, preferences, baseUrl, apiUrl)
    }

    private val mkissaExtractor by lazy { MKissaExtractor(mkissaClient, mkissaHeaders) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)

            val showId = findShowId(meta.anilistId, anime.title) ?: return emptyList()

            val translationType = resolveTranslationType()
            val episodeString = findEpisodeString(showId, meta.epNum, translationType)
                ?: findEpisodeString(showId, meta.epNum, if (translationType == "sub") "dub" else "sub")
                ?: return emptyList()

            val sourceUrls = fetchSourceUrls(showId, episodeString, translationType)
            if (sourceUrls.isEmpty()) return emptyList()

            extractVideos(sourceUrls)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun findShowId(anilistId: Int, title: String): String? {
        showIdCache[anilistId]?.let { return it }

        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                }
                put("limit", 10)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
            put("query", SEARCH_QUERY)
        }

        val result = mkissaClient.newCall(buildPost(data))
            .awaitSuccess()
            .parseAs<MKissaSearchResult>()

        val edges = result.data.shows.edges
        if (edges.isEmpty()) return null

        val titleLower = title.lowercase().trim()

        val best = edges.firstOrNull { edge ->
            listOfNotNull(edge.name, edge.englishName, edge.nativeName)
                .any { it.lowercase().trim() == titleLower }
        }
            ?: edges.minByOrNull { edge ->
                listOfNotNull(edge.name, edge.englishName, edge.nativeName)
                    .minOf { name ->
                        val n = name.lowercase().trim()
                        when {
                            n.startsWith(titleLower) -> n.length
                            titleLower.startsWith(n) -> n.length + 1000
                            n.contains(titleLower) -> n.length + 2000
                            else -> Int.MAX_VALUE
                        }
                    }
            }
            ?: edges.firstOrNull()

        val showId = best?.id ?: return null
        showIdCache[anilistId] = showId
        return showId
    }

    private suspend fun findEpisodeString(
        showId: String,
        epNum: Int,
        translationType: String,
    ): String? {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("_id", showId)
            }
            put("query", EPISODES_QUERY)
        }

        val result = mkissaClient.newCall(buildPost(data))
            .awaitSuccess()
            .parseAs<MKissaSeriesResult>()

        val episodes = if (translationType == "dub") {
            result.data.show.availableEpisodesDetail.dub
        } else {
            result.data.show.availableEpisodesDetail.sub
        } ?: return null

        return episodes.firstOrNull { it == epNum.toString() }
            ?: episodes.firstOrNull { it.toFloatOrNull()?.toInt() == epNum }
    }

    private suspend fun fetchSourceUrls(
        showId: String,
        episodeString: String,
        translationType: String,
    ): List<MKissaSourceUrl> {
        val encryptionChangedError = Exception("MKissa changed its stream encryption")
        var lastError: Throwable? = null
        var buildHealed = false

        repeat(MAX_KEY_ATTEMPTS) { attempt ->
            val material = runCatching { keyManager.material(forceRefresh = attempt > 0) }
                .getOrElse {
                    lastError = it
                    return@repeat
                }

            val variables = buildJsonObject {
                put("showId", showId)
                put("translationType", translationType)
                put("episodeString", episodeString)
            }

            val extensions = buildJsonObject {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", STREAM_HASH)
                }
                put("k", ANIME_LANE)
                put("aaReq", keyManager.aaReq(material))
            }

            val url = apiUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("query", STREAM_QUERY) // Added literal query text
                addQueryParameter("variables", variables.toJsonString())
                addQueryParameter("extensions", extensions.toJsonString())
            }.build()

            val streamHeaders = mkissaHeaders.newBuilder()
                .set("x-build-id", material.buildId)
                .build()

            val responseBody = runCatching {
                mkissaClient.newCall(GET(url, streamHeaders)).awaitSuccess().bodyString()
            }.getOrElse {
                lastError = it
                null
            }

            if (responseBody != null) {
                val tobeparsed = runCatching {
                    responseBody.parseAs<MKissaEncryptedResult>().data.tobeparsed
                }.getOrNull()

                // Handle NEED_CAPTCHA and other API errors gracefully
                if (tobeparsed.isNullOrBlank()) {
                    keyManager.apiErrorMessage(responseBody)?.let { throw Exception(it) }
                }

                when {
                    !tobeparsed.isNullOrBlank() -> {
                        runCatching {
                            keyManager.decrypt(tobeparsed, material)
                                ?.parseAs<MKissaDecryptedResult>()
                        }.getOrNull()
                            ?.let { return it.episode?.sourceUrls.orEmpty() }
                    }

                    !keyManager.isCryptoError(responseBody) -> {
                        runCatching {
                            responseBody.parseAs<MKissaEpisodeResult>()
                                .data.episode?.sourceUrls.orEmpty()
                        }.getOrNull()
                            ?.let { return it }
                    }
                }

                lastError = encryptionChangedError

                if (attempt >= 1 && !buildHealed && keyManager.isCryptoError(responseBody)) {
                    keyManager.invalidateBuild()
                    buildHealed = true
                }
            }
            keyManager.invalidate()
        }

        throw lastError ?: encryptionChangedError
    }

    private suspend fun extractVideos(sourceUrls: List<MKissaSourceUrl>): List<Video> {
        val mappings = listOf(
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc", "playtaku", "vidcloud"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru", "okru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
            "Fm-Hls" to listOf("bysekoze.com", "fastmoon", "filemoon", "moonplayer"), // Updated mapping
            "streamwish" to listOf("wish"),
        )

        val serverList = mutableListOf<Server>()
        sourceUrls.forEach { video ->
            val videoUrl = video.sourceUrl.decryptSource()

            val matchingMapping = mappings.firstOrNull { (_, urlMatches) ->
                videoUrl.containsAny(urlMatches)
            }

            when {
                videoUrl.startsWith("/apivtwo/") && INTERNAL_HOSTER_NAMES.any {
                    Regex("""\b${it.lowercase()}\b""").find(video.sourceName.lowercase()) != null
                } ->
                    Server(videoUrl, "internal ${video.sourceName}", video.priority)
                        .let(serverList::add)

                video.type == "player" ->
                    Server(videoUrl, "player@${video.sourceName}", video.priority)
                        .let(serverList::add)

                matchingMapping != null ->
                    Server(videoUrl, matchingMapping.first, video.priority)
                        .let(serverList::add)
            }
        }

        return serverList.parallelCatchingFlatMap { server ->
            val sName = server.sourceName
            when {
                sName.startsWith("internal ") ->
                    mkissaExtractor.videoFromUrl(server.sourceUrl, server.sourceName, PLAYER_DOMAIN)

                sName.startsWith("player@") -> {
                    val videoHeaders = mkissaHeaders.newBuilder().apply {
                        add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                        add("Host", server.sourceUrl.toHttpUrl().host)
                        add("Referer", "$PLAYER_DOMAIN/")
                    }.build()

                    Video(
                        server.sourceUrl,
                        "Original (player ${server.sourceName.substringAfter("player@")})",
                        server.sourceUrl,
                        headers = videoHeaders,
                    ).let(::listOf)
                }

                sName == "vidstreaming" ->
                    gogoStreamExtractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))

                sName == "doodstream" ->
                    doodExtractor.videosFromUrl(server.sourceUrl)

                sName == "okru" ->
                    okruExtractor.videosFromUrl(server.sourceUrl)

                sName == "mp4upload" ->
                    mp4uploadExtractor.videosFromUrl(server.sourceUrl, headers)

                sName == "streamlare" ->
                    streamlareExtractor.videosFromUrl(server.sourceUrl)

                sName == "Fm-Hls" -> // Updated extractor call
                    filemoonExtractor.videosFromUrl(server.sourceUrl, prefix = "Fm-Hls:")

                sName == "streamwish" ->
                    streamwishExtractor.videosFromUrl(server.sourceUrl, videoNameGen = { "StreamWish:$it" })

                else -> emptyList()
            }.map { v -> Pair(v, server.priority) }
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun resolveTranslationType(): String {
        val audioPref = preferences.getString("preferred_audio_type", "sub") ?: "sub"
        return if (audioPref == "dub") "dub" else "sub"
    }

    private fun buildPost(dataObject: kotlinx.serialization.json.JsonObject): Request {
        val payload = dataObject.toJsonString().toJsonBody()

        val postHeaders = mkissaHeaders.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", apiUrl.toHttpUrl().host)
            add("Origin", GRAPHQL_ORIGIN)
            add("Referer", "$GRAPHQL_ORIGIN/")
        }.build()

        return POST("$apiUrl/api", headers = postHeaders, body = payload)
    }

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#") -> substring(1) to 0
            else -> this to null
        }

        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this
        }

        if (keyType == null) {
            XOR_MASKS.forEach { mask ->
                val decrypted = String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
                if (decrypted.contains("/clock") || decrypted.contains("http")) return decrypted
            }
            return this
        }

        val mask = XOR_MASKS[keyType]
        return String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
    }

    private fun String.containsAny(keywords: List<String>): Boolean = keywords.any { this.contains(it) }

    private data class Server(
        val sourceUrl: String,
        val sourceName: String,
        val priority: Float,
    )

    companion object {
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val PLAYER_DOMAIN = "https://allanime.day"
        private const val MAX_KEY_ATTEMPTS = 3

        private val INTERNAL_HOSTER_NAMES = arrayOf(
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
}
