package eu.kanade.tachiyomi.animeextension.en.yomi

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import aniyomi.lib.playlistutils.PlaylistUtils
import java.net.URLEncoder

class Yomi : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Yomi"
    override val baseUrl = "https://yomi.to"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // --- POPULAR ---
    override fun popularAnimeRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/browse?sort=TRENDING_DESC&page=$page&_t=$timestamp", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a.anime-card[href^='/anime/']").map { element ->
            SAnime.create().apply {
                title = element.select("h3").firstOrNull()?.text()?.trim() ?: "Unknown"
                url = element.attr("href")
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("a:contains(Next), .pagination .next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // --- LATEST ---
    override fun latestUpdatesRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        // Homepage contains "New Today", so we fetch the homepage for latest
        return GET("$baseUrl/?_t=$timestamp", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a.new-ep-card").map { element ->
            val watchUrl = element.attr("href") // e.g., "/watch/184356/2"
            val animeId = watchUrl.substringAfter("/watch/").substringBefore("/")
            
            SAnime.create().apply {
                title = element.select("p.text-ani-text").text().trim()
                url = "/anime/$animeId" // Convert to detail URL so the app can load metadata
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("a:contains(Next), .pagination .next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // --- SEARCH ---
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val timestamp = System.currentTimeMillis()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encodedQuery&page=$page&_t=$timestamp", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a.anime-card[href^='/anime/'], a[href^='/anime/']").mapNotNull { element ->
            // Filter out footer/nav links by ensuring it's an actual card with an image
            if (element.select("img").isEmpty() && !element.hasClass("anime-card")) return@mapNotNull null
            
            SAnime.create().apply {
                title = element.select("h3, p.font-semibold").firstOrNull()?.text()?.trim() ?: "Unknown"
                url = element.attr("href")
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("a:contains(Next), .pagination .next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // --- DETAILS ---
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2, .anime-title").firstOrNull()?.text() ?: "Unknown Title"
            genre = document.select("a[href*='/browse?genre=']").joinToString(", ") { it.text() }
            
            val descBuilder = StringBuilder()
            val synopsis = document.select("p, .synopsis, .description").firstOrNull()?.text()?.trim()
            if (!synopsis.isNullOrEmpty()) {
                descBuilder.append(synopsis).append("\n\n")
            }
            
            val episodes = document.select(".episodes-count, span:contains(eps)").firstOrNull()?.text()?.trim()
            if (!episodes.isNullOrEmpty()) descBuilder.append("Episodes: $episodes\n")
            
            val studio = document.select(".studio, a[href*='/studio/']").firstOrNull()?.text()?.trim()
            if (!studio.isNullOrEmpty()) descBuilder.append("Studio: $studio\n")
            
            val statusText = document.select(".status, .badge").firstOrNull()?.text()?.trim() ?: ""
            status = parseStatus(statusText)
            
            description = descBuilder.toString().trim()
            thumbnail_url = document.select("img.cover, img.poster, img[alt]").firstOrNull()?.attr("abs:src")
        }
    }

    private fun parseStatus(status: String): Int {
        return when {
            status.contains("ongoing", ignoreCase = true) || status.contains("airing", ignoreCase = true) -> SAnime.ONGOING
            status.contains("completed", ignoreCase = true) || status.contains("finished", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // --- EPISODES ---
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeElements = document.select("div.grid a[href^='/watch/']")
        
        return episodeElements.mapIndexed { index, element ->
            SEpisode.create().apply {
                name = element.attr("title").ifEmpty { "Episode ${index + 1}" }
                url = element.attr("href")
                episode_number = (index + 1).toFloat()
                date_upload = element.select(".date, time").firstOrNull()?.attr("datetime")?.toLongOrNull() ?: 0L
            }
        }.reversed()
    }

    // --- VIDEO ---
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Dynamic Subtype Extraction
        val activeSubtype = document.select("button[aria-pressed='true'], button.bg-ani-accent").firstOrNull()?.text()?.trim()?.uppercase() ?: "SUB"
        val qualitySuffix = " ($activeSubtype)"

        // Extract iframe sources
        document.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.isNotBlank()) {
                try {
                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        masterHeaders = headers,
                        videoHeaders = headers
                    )
                    extracted.forEach { video ->
                        videoList.add(Video(video.url, "${video.quality}$qualitySuffix", video.url, video.headers))
                    }
                } catch (e: Exception) {
                    // Ignore extraction errors
                }
            }
        }

        // Fallback: Search for direct m3u8 in script tags if iframe extraction yields nothing
        if (videoList.isEmpty()) {
            val scriptData = document.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            m3u8Regex.findAll(scriptData).forEach { match ->
                val url = match.groupValues[1]
                try {
                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = url,
                        masterHeaders = headers,
                        videoHeaders = headers
                    )
                    extracted.forEach { video ->
                        videoList.add(Video(video.url, "${video.quality}$qualitySuffix", video.url, video.headers))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        return videoList.distinctBy { it.quality }
    }

    // --- PREFERENCES ---
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val pref = SwitchPreferenceCompat(screen.context).apply {
            key = "prefer_1080p"
            title = "Prefer 1080p quality"
            setDefaultValue(true)
        }
        screen.addPreference(pref)
    }
}
