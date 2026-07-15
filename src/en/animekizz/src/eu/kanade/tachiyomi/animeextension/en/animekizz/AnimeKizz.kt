package eu.kanade.tachiyomi.animeextension.en.animekizz

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.preferenceScreen
import keiyoushi.utils.switchPreference
import okhttp3.Request
import okhttp3.Response

class AnimeKizz : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeKizz"
    override val baseUrl = "https://animekizz.live"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun getFilterList() = AnimeKizzFilters.getFilterList()

    // ==================== Popular ====================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/catalog?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a[href^='/anime/']").map { element ->
            SAnime.create().apply {
                title = element.select("h3, .font-black, .truncate").text().ifBlank { element.text() }
                url = element.attr("href")
                thumbnail_url = element.select("img").absUrl("src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("a[href*='page=${page + 1}'], button:contains(Next), .pagination a:contains(2)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== Latest ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/catalog?sort=recently_updated&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==================== Search ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.firstInstanceOrNull<AnimeKizzFilters.GenreFilter>()
        val genre = genreFilter?.selectedValue ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        
        return GET("$baseUrl/catalog?search=${query.encodeUri()}$genreQuery&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==================== Details ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2").firstOrNull()?.text() ?: document.title()
            genre = document.select("a[href^='/catalog?genre=']").joinToString { it.text() }
            description = document.select("p.line-clamp-4, .synopsis, div.synopsis").text()
            thumbnail_url = document.select("img[referrerpolicy='no-referrer']").absUrl("src").ifBlank {
                document.select("img").absUrl("src")
            }
            status = parseStatus(document.text())
        }
    }

    private fun parseStatus(text: String): Int {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("airing") || lowerText.contains("releasing") -> SAnime.ONGOING
            lowerText.contains("completed") || lowerText.contains("finished") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ==================== Episodes ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("a[href^='/watch/']").map { element ->
            SEpisode.create().apply {
                val rawText = element.select("span.font-black, .ep-title, span.truncate").text().ifBlank { element.text() }
                name = Regex("EP\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(1)?.let { "Episode $it" } ?: rawText
                
                url = element.attr("href")
                val epNum = Regex("EP\\s*(\\d+)|Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.filter { it.isNotEmpty() }?.lastOrNull()?.toFloatOrNull() ?: 0F
                episode_number = epNum
                date_upload = 0L
            }
        }.reversed().distinctBy { it.url }
    }

    // ==================== Videos ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val html = document.html()

        // 1. Try to find iframes (in case the dynamic player injects them or falls back to them)
        document.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.isNotBlank()) {
                // Extractors can be integrated here if the site falls back to standard iframes
                // e.g., if (src.contains("filemoon", ignoreCase = true)) { ... }
            }
        }

        // 2. Fallback: Regex search for m3u8 or mp4 in the page source
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""")

        m3u8Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
            videoList.add(Video(url = url, quality = "m3u8", videoUrl = url, headers = headers))
        }

        mp4Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
            videoList.add(Video(url = url, quality = "mp4", videoUrl = url, headers = headers))
        }

        // 3. If no direct links found, provide a descriptive placeholder
        if (videoList.isEmpty()) {
            videoList.add(
                Video(
                    url = "https://example.com/placeholder.m3u8",
                    quality = "Dynamic Player (Requires manual extraction update)",
                    videoUrl = "https://example.com/placeholder.m3u8",
                    headers = headers
                )
            )
        }

        return videoList
    }

    // ==================== Preferences ====================
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        screen.preferenceScreen {
            switchPreference {
                key = "pref_quality_1080"
                title = "Prefer 1080p"
                defaultValue = true
            }
        }
    }
    
    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
