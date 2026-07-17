package eu.kanade.tachiyomi.animeextension.en.yomi

import android.content.Context
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addListPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class Yomi : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Yomi"
    override val baseUrl = "https://yomi.to"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val context: Context by lazy { Injekt.get() }

    // Force browser-like headers to try and bypass basic bot detection
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

    override val client: OkHttpClient =
        super.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(context, super.client))
            .build()

    override fun popularAnimeRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/browse?sort=TRENDING_DESC&page=$page&_t=$timestamp", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/?_t=$timestamp", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.firstInstanceOrNull<GenreFilter>()
        val genre = genreFilter?.selectedValue ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        val timestamp = System.currentTimeMillis()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        return GET("$baseUrl/search?q=$encodedQuery$genreQuery&page=$page&_t=$timestamp", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    // ==================== DIAGNOSTIC PARSER ====================
    private fun parseAnimeList(document: Document): AnimesPage {
        val html = document.html()
        val pageTitle = document.title()

        // 1. Explicitly check for Cloudflare / Bot Protection blocks
        if (pageTitle.contains("Cloudflare", ignoreCase = true) ||
            pageTitle.contains("Just a moment", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("cf-chl-opt", ignoreCase = true) ||
            html.contains("Checking your browser", ignoreCase = true)) {
            
            throw Exception("⛔ BLOCKED BY CLOUDFLARE: The site is preventing automated access. WebView works because your browser solves the challenge, but the extension cannot.")
        }

        // 2. Check if we actually received any anime links
        val rawLinks = document.select("a[href^='/anime/']")
        if (rawLinks.isEmpty()) {
            throw Exception("⛔ PARSING FAILED: No '/anime/' links found in the HTML. The site may have changed its layout or is returning an empty/block page.")
        }

        // 3. Parse the links
        val animes = rawLinks.mapNotNull { aTag ->
            runCatching {
                val url = aTag.attr("href")
                if (url.isBlank() || url == "/anime/" || url == "/") return@runCatching null

                val img = aTag.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: aTag.selectFirst("h3")?.text()?.trim()
                    ?: aTag.parent()?.selectFirst("h3")?.text()?.trim()
                    ?: "Unknown"

                if (title.isBlank() || title.length < 3) return@runCatching null

                val thumbnail = img?.attr("abs:src") ?: img?.attr("data-src") ?: ""

                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(url)
                    this.thumbnail_url = thumbnail
                }
            }.getOrNull()
        }.distinctBy { it.url }

        val hasNextPage = document.select("a[href*='page=2'], a:contains(Next), button:contains(Load More)").isNotEmpty() ||
                          (animes.isNotEmpty() && animes.size >= 18)
                          
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2, .anime-title").firstOrNull()?.text()?.trim() ?: document.title()
            thumbnail_url = document.select("img.cover, img.poster, img[alt]").firstOrNull()?.attr("abs:src")
            genre = document.select("a[href*='/browse?genre=']").joinToString(", ") { it.text() }
            
            val synopsis = document.select("p, .synopsis, .description").firstOrNull()?.text()?.trim() ?: ""
            val descBuilder = StringBuilder().apply { if (synopsis.isNotBlank()) append(synopsis).append("\n\n") }
            
            val metaBlocks = document.select("div.flex.flex-col.gap-1, .meta-blocks, .info-list")
            fun getMeta(label: String): String = metaBlocks.firstOrNull { 
                it.select("span, .label").firstOrNull()?.text()?.contains(label, ignoreCase = true) == true 
            }?.select("span, .value")?.lastOrNull()?.text()?.trim() ?: ""
            
            val studio = getMeta("Studio")
            val episodesCount = getMeta("Episodes")
            
            author = studio
            status = when {
                document.text().contains("Airing", ignoreCase = true) || document.text().contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
                document.text().contains("Completed", ignoreCase = true) || document.text().contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            
            val metaLines = mutableListOf<String>()
            if (episodesCount.isNotBlank()) metaLines.add("Episodes: $episodesCount")
            if (studio.isNotBlank()) metaLines.add("Studio: $studio")
            if (metaLines.isNotEmpty()) descBuilder.append(metaLines.joinToString("\n"))
            
            description = descBuilder.toString().trim()
        }
    }

    // ==================== EPISODES ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        return document.select("div.grid a[href^='/watch/']").mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (!href.contains("/watch/")) return@runCatching null
                
                val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 0f
                if (epNum <= 0f) return@runCatching null
                
                val title = element.attr("title").ifBlank { "Episode ${epNum.toInt()}" }
                
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = epNum
                    name = if (title.isNotBlank() && !title.startsWith("Episode", ignoreCase = true) && !title.startsWith("Ep", ignoreCase = true)) {
                        "Episode ${epNum.toInt()}: $title"
                    } else {
                        title
                    }
                    date_upload = 0L
                }
            }.getOrNull()
        }.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // ==================== VIDEOS ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val videoList = mutableListOf<Video>()
        val activeSubtype = document.select("button[aria-pressed='true'], button.bg-ani-accent").firstOrNull()?.text()?.trim()?.uppercase() ?: "SUB"
        val qualitySuffix = " ($activeSubtype)"

        document.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.isNotBlank()) {
                runCatching {
                    val extracted = aniyomi.lib.playlistutils.PlaylistUtils(client, headers).extractFromHls(
                        playlistUrl = src, masterHeaders = headers, videoHeaders = headers
                    )
                    extracted.forEach { video ->
                        videoList.add(Video(video.url, "${video.quality}$qualitySuffix", video.url, video.headers))
                    }
                }
            }
        }
        return videoList.distinctBy { it.quality }
    }

    override fun getFilterList(): AnimeFilterList = YomiFilters.getFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "pref_quality", title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"), default = "1080", summary = "%s"
        )
    }
}
