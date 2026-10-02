package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

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
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class AnimeOnsenProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AnimeOnsen"
    override val baseUrl = "https://www.animeonsen.xyz"

    companion object {
        private const val AUTH_URL = "https://auth.animeonsen.xyz/oauth/token"
        private const val API_URL = "https://api.animeonsen.xyz/v4"
        private const val SEARCH_URL = "https://search.animeonsen.xyz"
        private const val SITE_URL = "https://www.animeonsen.xyz"
        private const val CLIENT_ID = "f296be26-28b5-4358-b5a1-6259575e23b7"
        private const val CLIENT_SECRET = "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235"
        private const val AO_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.3"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // LOCAL DTOs
    // =================================================================

    @Serializable
    private data class TokenResponse(val access_token: String = "")

    @Serializable
    private data class MeilisearchResponse(val hits: List<SearchHit> = emptyList())

    @Serializable
    private data class SearchHit(
        val content_id: String = "",
        val content_title: String? = null,
        val content_title_en: String? = null,
        val content_title_jp: String? = null,
    )

    @Serializable
    private data class VideoData(
        val metadata: VideoMetaData = VideoMetaData(),
        val uri: VideoStreamData = VideoStreamData(),
    )

    @Serializable
    private data class VideoMetaData(val subtitles: Map<String, String> = emptyMap())

    @Serializable
    private data class VideoStreamData(
        val stream: String = "",
        val subtitles: Map<String, String> = emptyMap(),
    )

    // =================================================================
    // STATE — cached tokens and ID mappings
    // =================================================================

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var searchToken: String? = null

    private val contentIdCache = mutableMapOf<String, String>()

    // =================================================================
    // HEADERS
    // =================================================================

    private fun authHeaders() = Headers.Builder()
        .add("User-Agent", AO_USER_AGENT)
        .add("Accept", "application/json")
        .add("Origin", SITE_URL)
        .add("Referer", "$SITE_URL/")
        .build()

    private fun apiHeaders() = Headers.Builder()
        .add("User-Agent", AO_USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Authorization", "Bearer ${accessToken ?: ""}")
        .add("Origin", SITE_URL)
        .add("Referer", "$SITE_URL/")
        .build()

    private fun searchHeaders() = Headers.Builder()
        .add("User-Agent", AO_USER_AGENT)
        .add("Accept", "application/json")
        .add("Authorization", "Bearer ${searchToken ?: ""}")
        .add("Origin", SITE_URL)
        .add("Referer", "$SITE_URL/")
        .build()

    // =================================================================
    // STEP 1: OAuth2 client_credentials → access_token
    // =================================================================

    private suspend fun ensureAccessToken() {
        if (accessToken != null) return
        accessToken = fetchAccessToken()
    }

    private suspend fun fetchAccessToken(): String? {
        return try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("grant_type", "client_credentials")
                .build()

            val body = client.newCall(POST(AUTH_URL, authHeaders(), formBody))
                .awaitSuccess().bodyString()

            if (body.isBlank() || body.trimStart().startsWith("<")) return null
            body.parseAs<TokenResponse>().access_token.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Scrape search token from homepage <meta> tag
    // =================================================================

    private suspend fun ensureSearchToken() {
        if (searchToken != null) return
        searchToken = fetchSearchToken()
    }

    private suspend fun fetchSearchToken(): String? {
        return try {
            val html = client.newCall(GET(SITE_URL, authHeaders())).awaitSuccess().bodyString()
            val doc = Jsoup.parse(html)
            doc.selectFirst("meta[name=ao-search-token]")?.attr("content")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: Title search → content_id (cached)
    // =================================================================

    private suspend fun resolveContentId(meta: EpisodeMeta, title: String): String? {
        val cacheKey = if (meta.malId != 0) "mal:${meta.malId}" else "title:$title"
        contentIdCache[cacheKey]?.let { return it }

        ensureSearchToken()
        if (searchToken == null) return null

        val searchBody = buildJsonObject {
            put("q", title)
        }.toJsonRequestBody()

        val responseBody = try {
            client.newCall(
                POST("$SEARCH_URL/indexes/content/search", searchHeaders(), searchBody),
            ).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        val hits = try {
            responseBody.parseAs<MeilisearchResponse>().hits
        } catch (_: Exception) {
            return null
        }

        if (hits.isEmpty()) return null

        // Meilisearch ranks by relevance natively, but we do a quick sanity check
        val titleLower = title.lowercase()
        val match = hits.firstOrNull { hit ->
            hit.content_title_en?.lowercase()?.contains(titleLower) == true ||
                hit.content_title?.lowercase()?.contains(titleLower) == true ||
                hit.content_title_jp?.lowercase()?.contains(titleLower) == true
        } ?: hits.firstOrNull { hit ->
            val hitTitle = (hit.content_title_en ?: hit.content_title ?: "").lowercase()
            titleLower.split(" ").any { word -> word.length > 3 && hitTitle.contains(word) }
        } ?: hits.first()

        val contentId = match.content_id.takeIf { it.isNotBlank() } ?: return null

        contentIdCache[cacheKey] = contentId
        if (meta.malId != 0) {
            contentIdCache["title:$title"] = contentId
        }

        return contentId
    }

    // =================================================================
    // STEP 4: Fetch video URL + subtitles
    // =================================================================

    private suspend fun fetchVideoData(contentId: String, epNum: Int): VideoData? {
        ensureAccessToken()
        if (accessToken == null) return null

        val url = "$API_URL/content/$contentId/video/$epNum"

        var response = try {
            client.newCall(GET(url, apiHeaders())).awaitSuccess()
        } catch (_: Exception) {
            // Token might be expired — refresh and retry once
            accessToken = fetchAccessToken() ?: return null
            try {
                client.newCall(GET(url, apiHeaders())).awaitSuccess()
            } catch (_: Exception) {
                return null
            }
        }

        val body = response.bodyString()
        return try {
            body.parseAs<VideoData>()
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            var title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
            
            // Fallback to AniList if the title is completely blank
            if (title.isBlank()) {
                title = fetchTitleFromAniList(meta.anilistId) ?: return listOf(Video("debug://x", "Title blank (AL: ${meta.anilistId})", "debug://x"))
            }

            val contentId = resolveContentId(meta, title) ?: return listOf(Video("debug://x", "0 results for '$title'", "debug://x"))

            val videoData = fetchVideoData(contentId, meta.epNum) ?: return listOf(Video("debug://x", "No video data for ep ${meta.epNum}", "debug://x"))

            val streamUrl = videoData.uri.stream
            if (streamUrl.isBlank() || !streamUrl.startsWith("http")) {
                return listOf(Video("debug://x", "Invalid stream URL", "debug://x"))
            }

            val subtitleLangs = videoData.metadata.subtitles
            val subtitles = videoData.uri.subtitles.mapNotNull { (langCode, subUrl) ->
                val langName = subtitleLangs[langCode] ?: langCode
                if (subUrl.isNotBlank()) Track(subUrl, langName) else null
            }

            val vidHeaders = Headers.Builder()
                .add("User-Agent", AO_USER_AGENT)
                .add("Referer", "$SITE_URL/")
                .add("Origin", SITE_URL)
                .build()

            // Handle HLS vs Direct MP4 safely
            if (streamUrl.contains(".m3u8")) {
                playlistUtils.extractFromHls(
                    streamUrl,
                    videoNameGen = { quality -> "$name $quality" },
                    subtitleList = subtitles,
                    referer = "$SITE_URL/",
                    masterHeaders = vidHeaders,
                    videoHeaders = vidHeaders,
                )
            } else {
                listOf(Video(streamUrl, "$name Default", streamUrl, vidHeaders, subtitleTracks = subtitles))
            }
        } catch (t: Throwable) {
            listOf(Video("debug://x", "${t::class.simpleName}: ${t.message?.take(115)}", "debug://x"))
        }
    }

    // ==================== AniList Title Fetcher ====================
    @Serializable private data class AniListMediaResponse(val Media: AniListMediaFull? = null)
    @Serializable private data class AniListMediaFull(val title: AniListTitlesFull? = null)
    @Serializable private data class AniListTitlesFull(val english: String? = null, val romaji: String? = null)

    private suspend fun fetchTitleFromAniList(anilistId: Int): String? {
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject { put("id", anilistId) }
        return try {
            val request = graphQLPost("https://graphql.anilist.co", headers, query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (_: Exception) {
            null
        }
    }
}
