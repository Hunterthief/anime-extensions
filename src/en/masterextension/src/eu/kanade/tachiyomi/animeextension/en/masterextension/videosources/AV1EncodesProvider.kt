package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * AV1Encodes video source.
 *
 * Clean REST pipeline: Search → Episodes Page → Download Page (Token) → DDL API → DASH/Stream/DL.
 * No encryption, no JS execution, no anti-bot. Just HTML parsing + JSON API + redirect resolution.
 *
 * Flow:
 *   1. GET /search?q={title} → find anime slug
 *   2. GET /episodes/{slug}/1/1920%20x%201080 → find episode download link by filename
 *   3. GET {download_link} → regex extract ddl-token
 *   4. GET /get_ddl/{filename} with X-Ddl-Token header → JSON with stream/watch/dl links
 *   5. Resolve redirects → construct DASH MPD or direct stream URLs
 */
class AV1EncodesProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AV1Encodes"

    companion object {
        private const val BASE_URL = "https://av1encodes.com"
        private val TOKEN_REGEX = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""")
        private val EP_NUM_REGEX = Regex("""\[(?:S\d+-)?E(\d+)]""", RegexOption.IGNORE_CASE)
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private fun siteHeaders(referer: String = "$BASE_URL/") = headers.newBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", referer)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private fun apiHeaders(referer: String, token: String) = headers.newBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", referer)
        .set("Accept", "application/json")
        .set("X-Ddl-Token", token)
        .build()

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
    private data class DdlResponse(
        @SerialName("success") val success: Boolean = false,
        @SerialName("stream_link") val streamLink: String? = null,
        @SerialName("download_link") val downloadLink: String? = null,
        @SerialName("watch_link") val watchLink: String? = null,
        @SerialName("file_size") val fileSize: String? = null,
    )

    // =================================================================
    // STEP 1: Search → anime slug
    // =================================================================
    private suspend fun searchAnime(title: String): String? {
        val url = "$BASE_URL/search?q=${URLEncoder.encode(title, "UTF-8")}"
        val doc = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString().let { Jsoup.parse(it) }
        } catch (_: Exception) {
            return null
        }

        val cleanTitle = title.trim().lowercase()
        val match = doc.select("article.anime-card a[href*=/anime/], a[href*=/anime/]").firstOrNull { link ->
            val text = link.text().trim().lowercase()
            text == cleanTitle || text.contains(cleanTitle) || cleanTitle.contains(text)
        } ?: doc.selectFirst("article.anime-card a[href*=/anime/], a[href*=/anime/]")

        val href = match?.attr("abs:href") ?: match?.attr("href") ?: return null
        return href.removePrefix(BASE_URL).substringBefore("?")
    }

    // =================================================================
    // STEP 2: Episodes page → find episode download URL
    // =================================================================
    private suspend fun getEpisodeUrl(animeSlug: String, epNum: Int): String? {
        val encodedRes = "1920%20x%201080" // Default to 1080p
        val url = "$BASE_URL/episodes$animeSlug/1/$encodedRes"
        
        val doc = try {
            client.newCall(GET(url, siteHeaders("$BASE_URL/"))).awaitSuccess().bodyString().let { Jsoup.parse(it) }
        } catch (_: Exception) {
            return null
        }

        val match = doc.select("a[href*=/download/]").firstOrNull { link ->
            val href = link.attr("href")
            val filename = href.substringAfterLast("/").substringBefore("?")
            val decoded = URLDecoder.decode(filename, "UTF-8")
            EP_NUM_REGEX.find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull() == epNum
        }

        val href = match?.attr("href") ?: return null
        return if (href.startsWith("http")) href.removePrefix(BASE_URL) else href
    }

    // =================================================================
    // STEP 3: Download page → extract DDL token
    // =================================================================
    private suspend fun getDdlToken(episodePath: String): String? {
        val url = "$BASE_URL$episodePath"
        val html = try {
            client.newCall(GET(url, siteHeaders(url))).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }
        return TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
    }

    // =================================================================
    // STEP 4: DDL API → JSON with links
    // =================================================================
    private suspend fun getDdlLinks(encodedFilename: String, token: String, referer: String): DdlResponse? {
        val url = "$BASE_URL/get_ddl/$encodedFilename"
        val body = try {
            client.newCall(GET(url, apiHeaders(referer, token))).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }
        return try {
            body.parseAs<DdlResponse>()
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 5: Resolve redirects + construct URLs
    // =================================================================
    private suspend fun resolveRedirect(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val url = if (path.startsWith("/")) "$BASE_URL$path" else path
        return try {
            client.newCall(GET(url, siteHeaders(url))).awaitSuccess().use {
                it.request.url.toString()
            }
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
        if (title.isBlank()) return debugVideo("title is blank")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        // Step 1: Search
        val animeSlug = try { searchAnime(title) } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '$title'")

        // Step 2: Get episode URL
        val rawEpisodePath = try { getEpisodeUrl(animeSlug, epNum) } catch (e: Exception) {
            return debugVideo("getEpisodeUrl threw: ${e.message}")
        } ?: return debugVideo("no episode $epNum found for '$title'")
        
        val episodePath = if (rawEpisodePath.startsWith("/")) rawEpisodePath else "/$rawEpisodePath"

        // Step 3: Get DDL token
        val token = try { getDdlToken(episodePath) } catch (e: Exception) {
            return debugVideo("getDdlToken threw: ${e.message}")
        } ?: return debugVideo("ddl-token not found on download page")

        // Step 4: Get DDL links
        val encodedFilename = episodePath.substringBefore("?").substringAfterLast("/")
        val ddl = try { 
            getDdlLinks(encodedFilename, token, "$BASE_URL$episodePath") 
        } catch (e: Exception) {
            return debugVideo("getDdlLinks threw: ${e.message}")
        } ?: return debugVideo("get_ddl API failed or returned invalid JSON")

        if (!ddl.success) return debugVideo("get_ddl API returned success=false")

        // Step 5: Build videos
        val videos = mutableListOf<Video>()
        val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
        val qualLabel = "AV1 1080p$sizeLabel"

        // DASH (Most ExoPlayer-friendly)
        val watchUrl = resolveRedirect(ddl.watchLink)
        if (watchUrl != null && watchUrl.contains("/watch/")) {
            val dashBase = watchUrl.replace("/watch/", "/dash/")
            val mpdUrl = "$dashBase/manifest.mpd"
            videos.add(Video(mpdUrl, "$qualLabel · DASH", mpdUrl))
        }

        // Direct Stream
        val streamUrl = resolveRedirect(ddl.streamLink)
        if (streamUrl != null && streamUrl != watchUrl) {
            videos.add(Video(streamUrl, "$qualLabel · Stream", streamUrl))
        }

        // Direct Download
        val dlUrl = resolveRedirect(ddl.downloadLink)
        if (dlUrl != null) {
            videos.add(Video(dlUrl, "$qualLabel · Direct DL", dlUrl))
        }

        if (videos.isEmpty()) {
            return debugVideo("0 videos resolved from DDL API")
        }

        return videos
    }
}
