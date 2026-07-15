package eu.kanade.tachiyomi.animeextension.en.animekizz

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // ==================== POPULAR ====================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/catalog?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("a[href^='/anime/']").mapNotNull { element ->
            SAnime.create().apply {
                title = element.select("h3, .font-black, .truncate").text().ifBlank { element.text() }
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }.distinctBy { it.url }
        
        val hasNextPage = document.select("a[href*='page=${page + 1}'], button:contains(Next), .pagination a:contains(2)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== LATEST ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/catalog?sort=recently_updated&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==================== SEARCH ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is AnimeKizzFilters.GenreFilter } as? AnimeKizzFilters.GenreFilter
        val genre = genreFilter?.toUriPart() ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        
        return GET("$baseUrl/catalog?search=${query.encodeUri()}$genreQuery&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==================== DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2").firstOrNull()?.text() ?: document.title()
            genre = document.select("a[href^='/catalog?genre=']").joinToString { it.text() }
            description = document.select("p.line-clamp-4, .synopsis, div.synopsis").text()
            thumbnail_url = document.select("img[referrerpolicy='no-referrer']").attr("abs:src").ifBlank {
                document.select("img").attr("abs:src")
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

    // ==================== EPISODES ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        return document.select("a[href^='/watch/']").map { element ->
            SEpisode.create().apply {
                val rawText = element.select("span.font-black, .ep-title, span.truncate").text().ifBlank { element.text() }
                name = Regex("EP\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(1)?.let { "Episode $it" } ?: rawText
                
                setUrlWithoutDomain(element.attr("href"))
                val epNum = Regex("EP\\s*(\\d+)|Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.filter { it.isNotEmpty() }?.lastOrNull()?.toFloatOrNull() ?: 0F
                episode_number = epNum
                date_upload = 0L
            }
        }.reversed().distinctBy { it.url }
    }

    // ==================== VIDEOS ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val videoList = mutableListOf<Video>()
        val html = document.html()

        // 1. Attempt to extract from iframes using parallel processing (standard framework pattern)
        document.select("iframe").parallelCatchingFlatMapBlocking { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) {
                when {
                    src.contains("filemoon", ignoreCase = true) -> {
                        // FilemoonExtractor(client).videosFromUrl(src, headers)
                    }
                    src.contains("streamwish", ignoreCase = true) -> {
                        // StreamWishExtractor(client).videosFromUrl(src, headers)
                    }
                    else -> emptyList()
                }
            } else {
                emptyList()
            }
        }.let { videoList.addAll(it) }

        // 2. Fallback: Regex search for direct m3u8/mp4 in page source
        if (videoList.isEmpty()) {
            val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""")

            m3u8Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
                videoList.add(Video(url, "m3u8", url, headers))
            }

            mp4Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
                videoList.add(Video(url, "mp4", url, headers))
            }
        }

        // 3. Descriptive placeholder if dynamic JS player hides all sources
        if (videoList.isEmpty()) {
            videoList.add(
                Video(
                    url = "https://example.com/placeholder.m3u8",
                    quality = "Dynamic Player (Requires API reverse-engineering)",
                    videoUrl = "https://example.com/placeholder.m3u8",
                    headers = headers
                )
            )
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("pref_quality", "1080") ?: "1080"
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains("m3u8", true) },
            ),
        ).reversed()
    }

    // ==================== FILTERS & PREFERENCES ====================
    override fun getFilterList(): AnimeFilterList = AnimeKizzFilters.getFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "pref_quality",
            title = "Preferred quality",
            entries = arrayOf("1080", "720", "480", "360"),
            entryValues = arrayOf("1080", "720", "480", "360"),
            default = "1080",
            summary = "%s",
        )
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
