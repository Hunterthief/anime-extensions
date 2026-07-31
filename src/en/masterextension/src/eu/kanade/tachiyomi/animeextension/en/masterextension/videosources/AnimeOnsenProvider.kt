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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * AnimeOnsen video source.
 *
 * Clean REST pipeline: OAuth2 → title search → direct .m3u8 URL.
 * No JS execution, no encryption, no HTML scraping for video URLs.
 *
 * Flow:
 *   1. POST auth.animeonsen.xyz/oauth/token → access_token
 *   2. GET www.animeonsen.xyz → scrape search token from <meta>
 *   3. POST search.animeonsen.xyz/indexes/content/search → content_id
 *   4. GET api.animeonsen.xyz/v4/content/{id}/video/{ep} → .m3u8 + subtitles
 */
class AnimeOnsenProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AnimeOnsen"
    override val baseUrl = "https://auth.animeonsen.xyz"

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
    private data class TokenResponse(
        val access_token: String = "",
    )

    @Serializable
    private data class MeilisearchResponse(
        val hits: List<SearchHit> = emptyList(),
    )

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
    private data class VideoMetaData(
        val subtitles: Map<String, String> = emptyMap(),
    )

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

    // Cache: malId or anilistId → contentId (avoids re-searching per episode)
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
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-site")
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
            val html = client.newCall(GET(SITE_URL, authHeaders()))
                .awaitSuccess().bodyString()
            val doc = Jsoup.parse(html)
            doc.selectFirst("meta[name=ao-search-token]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: Title search → content_id (cached)
    // =================================================================

    private suspend fun resolveContentId(meta: EpisodeMeta): String? {
        // Check cache first (keyed on malId if available, else title)
        val cacheKey = if (meta.malId != 0) "mal:${meta.malId}" else "title:${meta.title}"
        contentIdCache[cacheKey]?.let { return it }

        ensureSearchToken()
        if (searchToken == null) return null

        val searchBody = buildJsonObject {
            put("q", meta.title)
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

        // Try to match by title (case-insensitive contains)
        val titleLower = meta.title.lowercase()
        val match = hits.firstOrNull { hit ->
            hit.content_title_en?.lowercase()?.contains(titleLower) == true ||
                hit.content_title?.lowercase()?.contains(titleLower) == true ||
                hit.content_title_jp?.lowercase()?.contains(titleLower) == true
        } ?: hits.firstOrNull { hit ->
            // Fuzzy: check if any word matches
            val hitTitle = (hit.content_title_en ?: hit.content_title ?: "").lowercase()
            titleLower.split(" ").any { word -> word.length > 3 && hitTitle.contains(word) }
        } ?: hits.first()

        val contentId = match.content_id.takeIf { it.isNotBlank() } ?: return null

        // Cache it
        contentIdCache[cacheKey] = contentId
        // Also cache by title if we used malId
        if (meta.malId != 0) {
            contentIdCache["title:${meta.title}"] = contentId
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
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        // Resolve content_id via search
        val contentId = resolveContentId(meta.copy(title = title)) ?: return emptyList()

        // Fetch video data
        val videoData = fetchVideoData(contentId, meta.epNum) ?: return emptyList()

        val streamUrl = videoData.uri.stream
        if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return emptyList()

        // Build subtitle tracks
        val subtitleLangs = videoData.metadata.subtitles
        val subtitles = videoData.uri.subtitles.mapNotNull { (langCode, subUrl) ->
            val langName = subtitleLangs[langCode] ?: langCode
            if (subUrl.isNotBlank()) Track(subUrl, langName) else null
        }

        // The stream is a direct .m3u8 — extract quality variants via PlaylistUtils
        val vidHeaders = Headers.Builder()
            .add("User-Agent", AO_USER_AGENT)
            .add("Referer", "$SITE_URL/")
            .add("Origin", SITE_URL)
            .build()

        return playlistUtils.extractFromHls(
            streamUrl,
            videoNameGen = { quality -> "$name $quality" },
            subtitleList = subtitles,
            referer = "$SITE_URL/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }
}
