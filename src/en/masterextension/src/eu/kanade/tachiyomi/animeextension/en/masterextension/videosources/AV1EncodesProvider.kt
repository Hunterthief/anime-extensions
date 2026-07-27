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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

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
    private suspend fun searchAnime(title: String): Pair<String?, String?> {
        val url = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .addQueryParameter("page", "1")
            .build().toString()
            
        val html = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return null to "network_err: ${e.message?.take(20)}"
        }
        
        val doc = Jsoup.parse(html)
        val cleanTitle = title.trim().lowercase()
        
        // The original extension parses cards like this:
        val cards = doc.select("article.anime-card, article[class*='card'], article[class*='anime']")
        if (cards.isNotEmpty()) {
            val matchCard = cards.firstOrNull { card ->
                val cardTitle = (card.selectFirst("h3, h4")?.text() ?: card.text()).trim().lowercase()
                cardTitle == cleanTitle || cardTitle.contains(cleanTitle) || cleanTitle.contains(cardTitle)
            } ?: cards.firstOrNull()
            
            val a = matchCard?.selectFirst("a[href*='/anime/']")
            val href = a?.attr("abs:href") ?: a?.attr("href")
            if (href != null) {
                val path = href.removePrefix(BASE_URL).substringBefore("?")
                return path.substringAfterLast("/") to null // returns "death-note"
            }
            return null to "cards_found_but_no_link"
        }
        
        // Fallback: h3-based selector
        val contentRoot = doc.selectFirst("main, #main, #content, .content, [class*='anime-list'], [class*='result'], section.animes") ?: doc
        val h3s = contentRoot.select("h3")
        if (h3s.isNotEmpty()) {
            val matchH3 = h3s.firstOrNull { h3 ->
                val text = h3.text().trim().lowercase()
                text == cleanTitle || text.contains(cleanTitle) || cleanTitle.contains(text)
            } ?: h3s.firstOrNull()
            
            val block = matchH3?.parent()
            val a = block?.selectFirst("a[href*='/anime/']") ?: block?.parent()?.selectFirst("a[href*='/anime/']")
            val href = a?.attr("abs:href") ?: a?.attr("href")
            if (href != null) {
                val path = href.removePrefix(BASE_URL).substringBefore("?")
                return path.substringAfterLast("/") to null
            }
            return null to "h3s_found_but_no_link"
        }
        
        return null to "no_results_in_html"
    }

    // =================================================================
    // STEP 2: Episodes page → find episode download URL (with resolution fallback)
    // =================================================================
    private suspend fun getEpisodeUrl(animeSlug: String, epNum: Int): Pair<String?, String?> {
        // Try multiple resolutions in case the encode group didn't release 1080p
        val resolutions = listOf("1920%20x%201080", "1280%20x%20720", "854%20x%20480", "640%20x%20360")
        
        for (encodedRes in resolutions) {
            val url = "$BASE_URL/episodes/$animeSlug/1/$encodedRes"
            val html = try {
                client.newCall(GET(url, siteHeaders("$BASE_URL/anime/$animeSlug"))).awaitSuccess().bodyString()
            } catch (_: Exception) {
                continue
            }
            
            val doc = Jsoup.parse(html)
            val links = doc.select("a[href*='/download/']")
            
            val matchLink = links.firstOrNull { link ->
                val href = link.attr("href")
                val filename = href.substringAfterLast("/").substringBefore("?")
                val decoded = URLDecoder.decode(filename, "UTF-8")
                EP_NUM_REGEX.find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull() == epNum
            }
            
            if (matchLink != null) {
                val href = matchLink.attr("href")
                return (if (href.startsWith("http")) href.removePrefix(BASE_URL) else href) to null
            }
            
            // Fallback: regex on raw HTML if links aren't rendered properly
            val filenameRegex = Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE)
            val filenames = filenameRegex.findAll(html).map { it.groupValues[1] }.toList()
            
            val matchFilename = filenames.firstOrNull { fn ->
                val decoded = URLDecoder.decode(fn, "UTF-8")
                EP_NUM_REGEX.find(decoded)?.groupValues?.getOrNull(1)?.toIntOrNull() == epNum
            }
            
            if (matchFilename != null) {
                val encodedFilename = URLEncoder.encode(matchFilename, "UTF-8").replace("+", "%20")
                return "/download/$animeSlug/1/$encodedRes/$encodedFilename" to null
            }
        }
        
        return null to "no_ep_${epNum}_found_in_any_res"
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
        val (animeSlug, searchErr) = try { 
            searchAnime(title) 
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        }
        
        if (animeSlug == null) {
            return debugVideo("search null for '$title' (${searchErr ?: "unknown"})")
        }

        // Step 2: Get episode URL
        val (rawEpisodePath, epErr) = try { 
            getEpisodeUrl(animeSlug, epNum) 
        } catch (e: Exception) {
            return debugVideo("getEpisodeUrl threw: ${e.message}")
        }
        
        if (rawEpisodePath == null) {
            return debugVideo("no episode $epNum found for '$title' (${epErr ?: "unknown"})")
        }
        
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
