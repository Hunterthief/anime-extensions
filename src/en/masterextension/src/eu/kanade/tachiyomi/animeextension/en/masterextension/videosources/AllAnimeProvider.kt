package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.allanime.AaApiError
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.allanime.AllAnimeKeyManager
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class AllAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "AllAnime"

    companion object {
        private const val API_URL = "https://api.mkissa.net"
        private const val SITE_URL = "https://mkissa.to"
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val PLAYER_DOMAIN = "https://allanime.day"
        private const val STREAM_HASH =
            "f4662f4b7510b26795dd53ef824a0bf1740fbbc5d1273fab18222ac831bca8d0"
        private const val MAX_KEY_ATTEMPTS = 3

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

        private val SEARCH_QUERY = """
            query(
                ${'$'}search: SearchInput
                ${'$'}limit: Int
                ${'$'}page: Int
                ${'$'}translationType: VaildTranslationTypeEnumType
                ${'$'}countryOrigin: VaildCountryOriginEnumType
            ) {
                shows(
                    search: ${'$'}search
                    limit: ${'$'}limit
                    page: ${'$'}page
                    translationType: ${'$'}translationType
                    countryOrigin: ${'$'}countryOrigin
                ) {
                    pageInfo {
                        total
                    }
                    edges {
                        _id
                        name
                        thumbnail
                        englishName
                        nativeName
                        slugTime
                    }
                }
            }
        """.trimIndent()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val keyManager by lazy {
        AllAnimeKeyManager(client, headers, preferences, SITE_URL, STREAM_HASH)
    }

    // =================================================================
    // DEBUG HELPER
    // =================================================================
    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // =================================================================
    // DTOs
    // =================================================================
    @Serializable
    private data class SearchResult(val data: SearchResultData) {
        @Serializable
        data class SearchResultData(val shows: SearchResultShows) {
            @Serializable
            data class SearchResultShows(val edges: List<SearchResultEdge>) {
                @Serializable
                data class SearchResultEdge(
                    @SerialName("_id") val id: String,
                    val name: String,
                    val englishName: String? = null,
                )
            }
        }
    }

    @Serializable
    private data class EpisodeResult(val data: DataEpisode) {
        @Serializable
        data class DataEpisode(val episode: Episode? = null) {
            @Serializable
            data class Episode(val sourceUrls: List<SourceUrl>) {
                @Serializable
                data class SourceUrl(
                    val sourceUrl: String,
                    val type: String,
                    val sourceName: String,
                    val priority: Float = 0F,
                )
            }
        }
    }

    @Serializable
    private data class EncryptedEpisodeResult(val data: EncryptedData) {
        @Serializable
        data class EncryptedData(val tobeparsed: String? = null)
    }

    @Serializable
    private data class DecryptedEpisodeResult(
        val episode: EpisodeResult.DataEpisode.Episode? = null,
    )

    @Serializable
    private data class VideoLink(val links: List<Link>) {
        @Serializable
        data class Link(
            val link: String,
            val hls: Boolean? = null,
            val mp4: Boolean? = null,
            val resolutionStr: String,
            val subtitles: List<Subtitles>? = null,
        ) {
            @Serializable
            data class Subtitles(val lang: String, val src: String, val label: String? = null)
        }
    }

    // =================================================================
    // HEADERS
    // =================================================================
    private fun buildPost(data: kotlinx.serialization.json.JsonObject): Request {
        val payload = data.toJsonString().toJsonBody()
        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", API_URL.toHttpUrl().host)
            add("Origin", GRAPHQL_ORIGIN)
            add("Referer", "$GRAPHQL_ORIGIN/")
        }.build()
        return POST("$API_URL/api", headers = postHeaders, body = payload)
    }

    // =================================================================
    // STEP 1: Search — match by name, not firstOrNull
    // =================================================================
    private suspend fun searchShow(title: String): String? {
        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                }
                put("limit", 26)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            }
            put("query", SEARCH_QUERY)
        }

        return try {
            val responseBody = client.newCall(buildPost(data))
                .awaitSuccess().bodyString()
            val result = responseBody.parseAs<SearchResult>()
            val edges = result.data.shows.edges

            // Match by exact name (case-insensitive), then englishName, then first
            val cleanTitle = title.trim()
            edges.firstOrNull {
                it.name.equals(cleanTitle, ignoreCase = true) ||
                    it.englishName.equals(cleanTitle, ignoreCase = true)
            }?.id
                ?: edges.firstOrNull {
                    it.name.contains(cleanTitle, ignoreCase = true) ||
                        it.englishName?.contains(cleanTitle, ignoreCase = true) == true
                }?.id
                ?: edges.firstOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Get source URLs — with aaReq crypto
    // =================================================================
    private suspend fun fetchSourceUrls(
        showId: String,
        epNum: Int,
    ): List<EpisodeResult.DataEpisode.Episode.SourceUrl> {
        var lastError: Throwable? = null
        var maskHealed = false

        repeat(MAX_KEY_ATTEMPTS) { attempt ->
            val material = keyManager.material(forceRefresh = attempt > 0)

            val variables = buildJsonObject {
                put("showId", showId)
                put("translationType", "sub")
                put("episodeString", epNum.toString())
            }

            val extensions = buildJsonObject {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", STREAM_HASH)
                }
                put("aaReq", keyManager.aaReq(material))
            }

            val url = API_URL.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("variables", variables.toJsonString())
                addQueryParameter("extensions", extensions.toJsonString())
            }.build()

            val getHeaders = headers.newBuilder().apply {
                set("Accept", "*/*")
                set("Referer", "$SITE_URL/anime/")
            }.build()

            val responseBody = runCatching {
                client.newCall(GET(url.toString(), getHeaders)).awaitSuccess().bodyString()
            }.getOrElse {
                lastError = it
                null
            }

            if (responseBody != null) {
                val tobeparsed = runCatching {
                    responseBody.parseAs<EncryptedEpisodeResult>().data.tobeparsed
                }.getOrNull()

                when {
                    !tobeparsed.isNullOrBlank() -> {
                        runCatching {
                            keyManager.decrypt(tobeparsed, material)
                                ?.parseAs<DecryptedEpisodeResult>()
                        }.getOrNull()
                            ?.let { return it.episode?.sourceUrls.orEmpty() }
                    }
                    !keyManager.isCryptoError(responseBody) -> {
                        runCatching {
                            responseBody.parseAs<EpisodeResult>().data.episode?.sourceUrls.orEmpty()
                        }.getOrNull()
                            ?.let { return it }
                    }
                }

                lastError = Exception("AllAnime stream encryption changed")

                if (attempt >= 1 && !maskHealed && keyManager.isCryptoError(responseBody)) {
                    maskHealed = keyManager.healMask()
                }
            }
            keyManager.invalidate()
        }

        throw lastError ?: Exception("AllAnime stream encryption changed")
    }

    // =================================================================
    // STEP 3: Extract from internal hosters
    // =================================================================
    private suspend fun extractFromInternal(
        sourceUrl: String,
        sourceName: String,
    ): List<Video> {
        val linkJson = client.newCall(
            GET(PLAYER_DOMAIN + sourceUrl.replace("/clock?", "/clock.json?")),
        ).awaitSuccess().parseAs<VideoLink>()

        return linkJson.links.parallelCatchingFlatMap { link ->
            val subtitles = link.subtitles?.map { sub ->
                val label = sub.label?.let { " - $it" } ?: ""
                Track(sub.src, Locale(sub.lang).displayLanguage + label)
            }.orEmpty()

            when {
                link.mp4 == true -> {
                    Video(
                        link.link,
                        "$name - ${link.resolutionStr} ($sourceName)",
                        link.link,
                        subtitleTracks = subtitles,
                    ).let(::listOf)
                }
                link.hls == true -> {
                    val masterHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", link.link.toHttpUrl().host)
                        .add("Origin", PLAYER_DOMAIN)
                        .add("Referer", "$PLAYER_DOMAIN/")
                        .build()
                    playlistUtils.extractFromHls(
                        link.link,
                        masterHeaders = masterHeaders,
                        videoHeaders = masterHeaders,
                        videoNameGen = { q -> "$name - $q - ${link.resolutionStr} ($sourceName)" },
                        subtitleList = subtitles,
                    )
                }
                else -> emptyList()
            }
        }
    }

    // =================================================================
    // XOR DECRYPTION
    // =================================================================
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

    // =================================================================
    // ENTRY POINT — DEBUG VERSION
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val showId = try {
            searchShow(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        }
        if (showId == null) return debugVideo("search null for '$title'")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        val sourceUrls = try {
            fetchSourceUrls(showId, epNum)
        } catch (e: Exception) {
            return debugVideo("fetchSourceUrls: ${e.message}")
        }
        if (sourceUrls.isEmpty()) return debugVideo("no sourceUrls for $showId ep$epNum")

        val allVideos = mutableListOf<Video>()
        val errors = mutableListOf<String>()

        sourceUrls.forEach { source ->
            try {
                val videoUrl = source.sourceUrl.decryptSource()
                val isInternal = videoUrl.startsWith("/apivtwo/") && INTERAL_HOSTER_NAMES.any {
                    Regex("""\b${it.lowercase()}\b""").find(source.sourceName.lowercase()) != null
                }
                if (isInternal) {
                    allVideos.addAll(extractFromInternal(videoUrl, source.sourceName))
                } else {
                    errors.add("skip:${source.sourceName}")
                }
            } catch (e: Exception) {
                errors.add("err:${source.sourceName}:${e.message}")
            }
        }

        if (allVideos.isEmpty()) {
            return debugVideo("0 videos. ${errors.take(3).joinToString(" | ")}")
        }
        return allVideos
    }
}
