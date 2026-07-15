package eu.kanade.tachiyomi.animeextension.en.yomi

import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.playlistutils.PlaylistUtils
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
import java.net.URLEncoder

class Yomi : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Yomi"
    override val baseUrl = "https://yomi.to"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // CORRECT CloudflareInterceptor initialization: (OkHttpClient, String)
    override val client: OkHttpClient =
        super.client.newBuilder()
            .addInterceptor(
                CloudflareInterceptor(
                    super.client,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
            )
            .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Cache-Control", "no-cache, no-store, must-revalidate")
        .add("Pragma", "no-cache")
        .add("Expires", "0")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

    // ==================== POPULAR ====================
    override fun popularAnimeRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/browse?sort=TRENDING_DESC&page=$page&_t=$timestamp", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    // ==================== LATEST ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/?_t=$timestamp", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("a[href*='/watch/'], a[href*='/anime/']").mapNotNull { element ->
            runCatching {
                val url = element.attr("href")
                if (url.isBlank() || url == "/") return@runCatching null
                
                val img = element.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("p, h3, .title")?.text()?.trim()
                    ?: "Unknown"
                    
                if (title.isBlank() || title.length < 3) return@runCatching null
                
                val animeId = url.substringAfter("/watch/").substringAfter("/anime/").substringBefore("/")
                val detailUrl = if (url.contains("/watch/")) "/anime/$animeId" else url
                
                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(detailUrl)
                    this.thumbnail_url = img?.attr("abs:src") ?: img?.attr("data-src") ?: ""
                }
            }.getOrNull()
        }.distinctBy { it.url }.take(50)
        
        return AnimesPage(animes, false)
    }

    // ==================== SEARCH ====================
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

    // ==================== SHARED PARSER ====================
    private fun parseAnimeList(document: Document): AnimesPage {
        val animes = document.select("a[href^='/anime/']").mapNotNull { element ->
            runCatching {
                val url = element.attr("href")
                if (url.isBlank() || url == "/anime/" || url == "/") return@runCatching null
                
                val img = element.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("h3, p, .title")?.text()?.trim()
                    ?: element.text().trim().take(50)
                    
                if (title.isBlank() || title.length < 3) return@runCatching null
                
                val thumbnail = img?.attr("abs:src") ?: img?.attr("data-src") ?: ""
                
                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(url)
                    this.thumbnail_url = thumbnail
                }
            }.getOrNull()
        }.distinctBy { it.url }
        
        // Fallback detection if primary selector yields nothing but page has anime links
        if (animes.isEmpty()) {
            val fallbackAnimes = document.select("a").mapNotNull { element ->
                runCatching {
                    val url = element.attr("href")
                    if (url.contains("/anime/")) {
                        val title = element.text().trim().take(50)
                        if (title.length >= 3) {
                            SAnime.create().apply {
                                this.title = "[FALLBACK] $title"
                                setUrlWithoutDomain(url)
                            }
                        } else null
                    } else null
                }.getOrNull()
            }.distinctBy { it.url }.take(10)
            
            if (fallbackAnimes.isNotEmpty()) return AnimesPage(fallbackAnimes, false)
        }
        
        val hasNextPage = document.select("a[href*='page=2'], button:contains(Next), a:contains(Next)").isNotEmpty() ||
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
            
            val metaBlocks = document.select("div.flex.flex-col.gap-1, .meta-blocks, .info-list")
            fun getMeta(label: String): String {
                return metaBlocks.firstOrNull { 
                    it.select("span, .label").firstOrNull()?.text()?.contains(label, ignoreCase = true) == true 
                }?.select("span, .value")?.lastOrNull()?.text()?.trim() ?: ""
            }
            
            val studio = getMeta("Studio")
            val episodesCount = getMeta("Episodes")
            
            author = studio
            status = when {
                document.text().contains("Airing", ignoreCase = true) || document.text().contains("Releasing", ignoreCase = true) || document.text().contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
                document.text().contains("Completed", ignoreCase = true) || document.text().contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            
            val descBuilder = StringBuilder()
            if (synopsis.isNotBlank()) descBuilder.append(synopsis).append("\n\n")
            
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
                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        masterHeaders = headers,
                        videoHeaders = headers
                    )
                    extracted.forEach { video ->
                        videoList.add(Video(video.url, "${video.quality}$qualitySuffix", video.url, video.headers))
                    }
                }
            }
        }

        if (videoList.isEmpty()) {
            val scriptData = document.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            m3u8Regex.findAll(scriptData).forEach { match ->
                val url = match.groupValues[1]
                runCatching {
                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = url,
                        masterHeaders = headers,
                        videoHeaders = headers
                    )
                    extracted.forEach { video ->
                        videoList.add(Video(video.url, "${video.quality}$qualitySuffix", video.url, video.headers))
                    }
                }
            }
        }

        return videoList.distinctBy { it.quality }
    }

    // ==================== PREFERENCES & FILTERS ====================
    override fun getFilterList(): AnimeFilterList = YomiFilters.getFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "pref_quality",
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = "1080",
            summary = "%s",
        )
    }
}
