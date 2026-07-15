package eu.kanade.tachiyomi.animeextension.en.animekizz

import android.app.Application
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreference
import keiyoushi.utils.preferenceScreen
import keiyoushi.utils.switchPreference
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeKizz : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeKizz"
    override val baseUrl = "https://animekizz.live"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getPreference("animekizz_prefs")
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

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

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/catalog?sort=recently_updated&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.find { it is AnimeKizzFilters.GenreFilter } as? AnimeKizzFilters.GenreFilter
        val genre = genreFilter?.selectedValue ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        
        return GET("$baseUrl/catalog?search=${query.encodeUri()}$genreQuery&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2.text-2xl, h2.text-3xl").firstOrNull()?.text() ?: document.title()
            genre = document.select("a[href^='/catalog?genre=']").joinToString(", ") { it.text() }
            description = document.select("p.line-clamp-4, .synopsis, div.synopsis").text()
            thumbnail_url = document.select("img[referrerpolicy='no-referrer']").absUrl("src").ifBlank {
                document.select("img").absUrl("src")
            }
            status = parseStatus(document.text())
        }
    }

    private fun parseStatus(text: String): Int {
        return when {
            text.contains("Airing", ignoreCase = true) || text.contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
            text.contains("Completed", ignoreCase = true) || text.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

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
                date_upload = -1L
            }
        }.reversed().distinctBy { it.url }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val html = response.body.string()

        // 1. Try to find iframes (in case the dynamic player injects them or falls back to them)
        document.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.isNotBlank()) {
                if (src.contains("filemoon", ignoreCase = true)) {
                    // videoList.addAll(FilemoonExtractor(client).videosFromUrl(src, headers))
                } else if (src.contains("streamwish", ignoreCase = true)) {
                    // videoList.addAll(StreamWishExtractor(client).videosFromUrl(src, headers))
                }
            }
        }

        // 2. Fallback: Regex search for m3u8 or mp4 in the page source or JS variables
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""")

        m3u8Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
            videoList.add(Video(url, "m3u8", url, headers))
        }

        mp4Regex.findAll(html).map { it.groupValues[1] }.distinct().forEach { url ->
            videoList.add(Video(url, "mp4", url, headers))
        }

        // 3. If no direct links found, provide a descriptive placeholder
        if (videoList.isEmpty()) {
            videoList.add(
                Video(
                    "https://example.com/placeholder.m3u8",
                    "Dynamic Player (Requires manual extraction update)",
                    "https://example.com/placeholder.m3u8",
                    headers
                )
            )
        }

        return videoList
    }

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
