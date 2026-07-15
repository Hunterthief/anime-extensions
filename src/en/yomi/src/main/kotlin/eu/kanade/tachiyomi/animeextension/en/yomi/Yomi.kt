package eu.kanade.tachiyomi.animeextension.en.yomi

import android.app.Application
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.preferenceScreen
import keiyoushi.utils.switchPreference
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import aniyomi.lib.omniembedextractor.OmniEmbedExtractor
import aniyomi.lib.playlistutils.PlaylistUtils

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

    override fun popularAnimeRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/browse?sort=TRENDING_DESC&page=$page&_t=$timestamp", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a[href^='/anime/']").map { element ->
            SAnime.create().apply {
                title = element.select("h3, .title").firstOrNull()?.text() ?: "Unknown Title"
                url = element.attr("href")
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("button:contains(Load More), a:contains(Load More), .pagination .next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/browse?sort=START_DATE_DESC&page=$page&_t=$timestamp", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val timestamp = System.currentTimeMillis()
        val queryParams = mutableListOf<String>()
        
        if (query.isNotBlank()) {
            queryParams.add("q=$query")
        }
        
        var sortParam = "TRENDING_DESC"
        
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.selectedValue.isNotBlank()) {
                        queryParams.add("genre=${filter.selectedValue}")
                    }
                }
                is FormatFilter -> {
                    if (filter.selectedValue.isNotBlank()) {
                        queryParams.add("format=${filter.selectedValue}")
                    }
                }
                is SortFilter -> {
                    sortParam = filter.selectedValue.ifBlank { "TRENDING_DESC" }
                }
                else -> {}
            }
        }
        
        queryParams.add("sort=$sortParam")
        queryParams.add("page=$page")
        queryParams.add("_t=$timestamp")
        
        return GET("$baseUrl/browse?${queryParams.joinToString("&")}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        // Adaptive episode scraping: read directly from the grid on the detail page
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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Dynamic Subtype Extraction
        val activeSubtype = document.select("button[aria-pressed='true'], button.bg-ani-accent").firstOrNull()?.text()?.trim()?.uppercase() ?: "SUB"
        
        // Extract iframe sources
        val iframes = document.select("iframe")
        iframes.forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) {
                try {
                    val extractedVideos = OmniEmbedExtractor(client).videosFromUrl(src, headers)
                    extractedVideos.forEach { video ->
                        val qualityWithSubtype = "${video.quality} ($activeSubtype)"
                        videoList.add(Video(video.url, qualityWithSubtype, video.url, video.headers))
                    }
                } catch (e: Exception) {
                    if (src.contains(".m3u8", ignoreCase = true)) {
                        videoList.addAll(playlistUtils.extractFromHls(src, videoName = "Playlist ($activeSubtype)"))
                    }
                }
            }
        }

        // Fallback: Search for direct m3u8 in script tags if iframe extraction fails
        if (videoList.isEmpty()) {
            val scriptData = document.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            m3u8Regex.findAll(scriptData).forEach { match ->
                val url = match.groupValues[1]
                videoList.addAll(playlistUtils.extractFromHls(url, videoName = "Direct ($activeSubtype)"))
            }
        }

        return videoList.distinctBy { it.quality }
    }

    override fun videoListSelector() = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.preferenceScreen {
            switchPreference {
                key = "prefer_1080p"
                title = "Prefer 1080p quality"
                defaultValue = true
            }
        }
    }
}
