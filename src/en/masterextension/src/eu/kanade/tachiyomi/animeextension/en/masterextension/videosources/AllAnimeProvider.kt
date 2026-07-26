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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
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
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AllAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AllAnime"

    companion object {
        private const val API_URL = "https://api.allanime.day"
        private const val SITE_URL = "https://allmanga.to"
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val FALLBACK_PLAYER_DOMAIN = "https://blog.allanime.day"

        private const val DECRYPT_SECRET = "Xot36i3lK3"
        private const val DECRYPT_TAG_LENGTH = 128
        private const val DECRYPT_KEY_ALGO = "SHA-256"
        private const val DECRYPT_KEY_TYPE = "AES"
        private const val DECRYPT_CIPHER_ALGO = "AES/GCM/NoPadding"

        private const val STREAM_HASH =
            "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

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
    private data class VersionResponse(val episodeIframeHead: String)

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
    // buildPost — exact match to working extension
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
    // STEP 1: Search — tries multiple title variants
    // =================================================================

    private suspend fun searchShow(title: String): String? {
        // Try the raw title first, then cleaned variants
        val titlesToTry = listOf(
            title,
            title.replace(Regex("\\(.*?\\)"), "").trim(),
            title.replace(Regex("\\[.*?\\]"), "").trim(),
            title.replace(Regex("Season \\d+", RegexOption.IGNORE_CASE), "").trim(),
            title.replace(Regex("\\d+(st|nd|rd|th)\\s+Season", RegexOption.IGNORE_CASE), "").trim(),
        ).distinct().filter { it.isNotBlank() }

        for (searchTitle in titlesToTry) {
            val data = buildJsonObject {
                putJsonObject("variables") {
                    putJsonObject("search") {
                        put("query", searchTitle)
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

            try {
                val responseBody = client.newCall(buildPost(data))
                    .awaitSuccess().bodyString()
                val result = responseBody.parseAs<SearchResult>()
                val showId = result.data.shows.edges.firstOrNull()?.id
                if (showId != null) return showId
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Get source URLs
    // =================================================================

    private suspend fun getSourceUrls(
        showId: String,
        epNum: Int,
        translationType: String,
    ): List<EpisodeResult.DataEpisode.Episode.SourceUrl> {
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", translationType)
            put("episodeString", epNum.toString())
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

        val responseBody = client.newCall(GET(url, headers))
            .awaitSuccess().bodyString()

        val tobeparsed = runCatching {
            responseBody.parseAs<EncryptedEpisodeResult>().data.tobeparsed
        }.getOrNull()

        return if (!tobeparsed.isNullOrBlank()) {
            decryptTobeparsed(tobeparsed)
                .parseAs<DecryptedEpisodeResult>()
                .episode?.sourceUrls
        } else {
            responseBody.parseAs<EpisodeResult>()
                .data.episode?.sourceUrls
        } ?: emptyList()
    }

    // =================================================================
    // STEP 3: Extract — NO FILTER, try everything
    // =================================================================

    private suspend fun extractFromInternal(
        sourceUrl: String,
        sourceName: String,
        endPoint: String,
    ): List<Video> {
        val linkJson = client.newCall(
            GET(endPoint + sourceUrl.replace("/clock?", "/clock.json?")),
        ).awaitSuccess().parseAs<VideoLink>()

        return linkJson.links.flatMap { link ->
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
                        .add("Origin", endPoint)
                        .add("Referer", "$endPoint/")
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
    // CRYPTO
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

    // =================================================================
    // HELPERS
    // =================================================================

    private suspend fun getIframeEndpoint(): String {
        return runCatching {
            client.newCall(GET("$SITE_URL/getVersion", headers))
                .awaitSuccess()
                .parseAs<VersionResponse>()
                .episodeIframeHead
        }.getOrDefault(FALLBACK_PLAYER_DOMAIN)
    }

    // =================================================================
    // ENTRY POINT — no filters, try everything
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        // Ensure epNum is at least 1
        val epNum = if (meta.epNum > 0) meta.epNum else 1

        return try {
            val showId = searchShow(title) ?: return emptyList()
            val sourceUrls = getSourceUrls(showId, epNum, "sub")
            if (sourceUrls.isEmpty()) return emptyList()

            val iframeEndpoint = getIframeEndpoint()

            // NO FILTER — try every source URL
            sourceUrls.flatMap { source ->
                runCatching {
                    val videoUrl = source.sourceUrl.decryptSource()
                    when {
                        videoUrl.startsWith("/apivtwo/") ->
                            extractFromInternal(videoUrl, source.sourceName, iframeEndpoint)
                        videoUrl.startsWith("http") && videoUrl.contains(".m3u8") ->
                            playlistUtils.extractFromHls(
                                videoUrl,
                                videoNameGen = { "$name - $it (${source.sourceName})" },
                            )
                        else -> emptyList()
                    }
                }.getOrElse { emptyList() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
