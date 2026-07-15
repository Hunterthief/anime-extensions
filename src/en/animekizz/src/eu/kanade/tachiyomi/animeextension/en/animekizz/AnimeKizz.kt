package eu.kanade.tachiyomi.animeextension.en.animekizz

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.addListPreference
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document

class AnimeKizz : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeKizz"
    override val baseUrl = "https://animekizz.live"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Cache-Control", "no-cache, no-store, must-revalidate")
        .add("Pragma", "no-cache")
        .add("Expires", "0")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // ==================== POPULAR ====================
    override fun popularAnimeRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/catalog?page=$page&_t=$timestamp", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(response, document)
    }

    // ==================== LATEST ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val timestamp = System.currentTimeMillis()
        return GET("$baseUrl/catalog?sort=recently_updated&page=$page&_t=$timestamp", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(response, document)
    }

    // ==================== SEARCH ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.firstInstanceOrNull<AnimeKizzFilters.GenreFilter>()
        val genre = genreFilter?.toUriPart() ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        val timestamp = System.currentTimeMillis()
        
        return GET("$baseUrl/catalog?q=${query.encodeUri()}$genreQuery&page=$page&_t=$timestamp", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(response, document)
    }

    // ==================== SHARED PARSER ====================
    private fun parseAnimeList(response: Response, document: Document): AnimesPage {
        val animes = document.select("a[href^='/anime/']").mapNotNull { aTag ->
            val url = aTag.attr("href")
            if (url.isBlank() || url == "/anime/") return@mapNotNull null
            
            val img = aTag.selectFirst("img")
            val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: aTag.selectFirst("h3")?.text()?.trim()
                ?: aTag.parent()?.selectFirst("h3")?.text()?.trim()
                ?: ""
                
            if (title.isBlank()) return@mapNotNull null
            
            val thumbnail = img?.attr("abs:src") ?: ""
            
            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(url)
                this.thumbnail_url = thumbnail
            }
        }.distinctBy { it.url }
        
        val currentUrl = response.request.url.toString()
        val currentPage = Regex("page=(\\d+)").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        val hasNextPage = document.select("a[href*='page=${currentPage + 1}']").isNotEmpty() ||
                          document.select("button:contains(Next), a:contains(Next), [aria-label*='next' i]").isNotEmpty() ||
                          (animes.isNotEmpty() && animes.size >= 18)
        
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.select("h1").firstOrNull()?.text()?.trim() ?: document.title()
            
            thumbnail_url = document.select("img[src*='/cover/']").firstOrNull()?.attr("abs:src")
                ?: document.select("img").firstOrNull { it.attr("src").contains("anilist.co", ignoreCase = true) }?.attr("abs:src")
                ?: document.select("img").firstOrNull()?.attr("abs:src")
            
            genre = document.select("a[href*='/catalog?genre=']").joinToString { it.text() }
            
            val synopsis = document.select("p.line-clamp-4, .synopsis, div.synopsis").firstOrNull()?.text()?.trim() ?: ""
            
            // 1. Extract top badges (Type, Status, Score)
            val badges = document.select("div.flex.flex-wrap.items-center.gap-3.mb-2 > span")
            val type = badges.getOrNull(0)?.text()?.trim() ?: ""
            val statusText = badges.getOrNull(1)?.text()?.trim() ?: ""
            val score = badges.getOrNull(2)?.text()?.trim() ?: ""
            
            // 2. Extract metadata blocks (Studio, Season, Source, Episodes)
            val metaBlocks = document.select("div.flex.flex-col.gap-1")
            fun getMeta(label: String): String {
                return metaBlocks.firstOrNull { 
                    it.select("span").firstOrNull()?.text()?.contains(label, ignoreCase = true) == true 
                }?.select("span")?.lastOrNull()?.text()?.trim() ?: ""
            }
            
            val studio = getMeta("Studio")
            val season = getMeta("Season")
            val episodesCount = getMeta("Episodes")
            val source = getMeta("Source")
            
            // Assign to dedicated fields
            author = studio
            status = when {
                statusText.contains("Airing", ignoreCase = true) || statusText.contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("Completed", ignoreCase = true) || statusText.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            
            // 3. Build clean, formatted description with metadata ALWAYS at the end
            val descBuilder = StringBuilder()
            if (synopsis.isNotBlank()) {
                descBuilder.append(synopsis).append("\n\n")
            }
            
            val metaLines = mutableListOf<String>()
            if (type.isNotBlank()) metaLines.add("Type: $type")
            if (statusText.isNotBlank()) metaLines.add("Status: $statusText")
            if (score.isNotBlank()) metaLines.add("Score: $score")
            if (season.isNotBlank()) metaLines.add("Aired: $season")
            if (episodesCount.isNotBlank()) metaLines.add("Episodes: $episodesCount")
            if (studio.isNotBlank()) metaLines.add("Studio: $studio")
            if (source.isNotBlank()) metaLines.add("Source: $source")
            
            if (metaLines.isNotEmpty()) {
                descBuilder.append(metaLines.joinToString("\n"))
            }
            
            description = descBuilder.toString().trim()
        }
    }

    // ==================== EPISODES (Parsed directly from Metadata Page) ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val baseUrl = response.request.url.toString().substringBefore("?") // Clean base URL
        var page = 1
        var hasMore = true
        
        while (hasMore && page <= 10) { // Limit to 10 pages to prevent infinite loops
            val url = if (page == 1) baseUrl else "$baseUrl?page=$page"
            val res = client.newCall(GET(url, headers)).execute()
            val document = res.useAsJsoup()
            
            val pageEpisodes = document.select("a[href^='/watch/']").mapNotNull { element ->
                val href = element.attr("href")
                if (!href.contains("/watch/")) return@mapNotNull null
                
                // Extract episode number from the button's text (e.g., "EP 1")
                val epButton = element.selectFirst("button[aria-label^='Open details for episode']")
                val epText = epButton?.text() ?: ""
                val epMatch = Regex("EP\\s*(\\d+)", RegexOption.IGNORE_CASE).find(epText)
                val epNum = epMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                
                // Extract episode title directly from the metadata page HTML
                val title = element.selectFirst("span.block.w-full.min-w-0.font-black.truncate")?.text()?.trim() 
                    ?: element.selectFirst("span.truncate")?.text()?.trim() 
                    ?: ""
                
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = epNum
                    name = if (title.isNotBlank() && !title.startsWith("Episode", ignoreCase = true)) {
                        "Episode ${epNum.toInt()}: $title"
                    } else {
                        "Episode ${epNum.toInt()}"
                    }
                    date_upload = 0L
                }
            }.distinctBy { it.url }
            
            // Check if we actually got new episodes that we haven't seen yet
            val existingUrls = episodes.map { it.url }.toSet()
            val newEpisodes = pageEpisodes.filter { it.url !in existingUrls }
            
            if (newEpisodes.isEmpty()) {
                hasMore = false // Stop pagination if no new episodes are found
            } else {
                episodes.addAll(newEpisodes)
            }
            page++
        }
        
        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // ==================== VIDEOS ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val videoList = mutableListOf<Video>()
        
        val currentUrl = response.request.url.toString()
        val slug = currentUrl.substringAfter("/watch/").substringBeforeLast("-episode-")
        val epNum = currentUrl.substringAfterLast("-episode-").substringBefore("/").substringBefore("?")
        
        val anilistLink = document.select("a[href*='anilist.co/anime/']").attr("href")
        val anilistId = if (anilistLink.isNotBlank()) anilistLink.substringAfterLast("/") else "0"
        val episodeId = if (anilistId != "0") "$slug-$anilistId:$epNum" else "$slug:$epNum"
        
        val servers = listOf("mimi:sub", "yuki:sub", "sora:sub", "beep:sub", "uwu:sub", "kiwi:sub", "mimi:dub", "yuki:dub")
        
        servers.forEach { serverId ->
            try {
                val resolveUrl = "$baseUrl/api/v1/video/resolve"
                val jsonBody = """{"episode_id":"$episodeId","server_id":"$serverId"}"""
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val resolveRequest = POST(resolveUrl, headers, requestBody)
                val resolveResponse = client.newCall(resolveRequest).execute()
                
                if (resolveResponse.isSuccessful) {
                    val json = Json.parseToJsonElement(resolveResponse.body.string()).jsonObject
                    val sources = json["sources"]?.jsonArray
                    
                    sources?.forEach { sourceElement ->
                        val source = sourceElement.jsonObject
                        val videoPath = source["url"]?.jsonPrimitive?.content ?: return@forEach
                        val quality = source["quality"]?.jsonPrimitive?.content ?: "Auto"
                        val serverName = source["server"]?.jsonPrimitive?.content ?: serverId
                        
                        val subtitleTracks = mutableListOf<Track>()
                        val subtitles = source["subtitles"]?.jsonArray
                        subtitles?.forEach { subElement ->
                            val sub = subElement.jsonObject
                            val subPath = sub["url"]?.jsonPrimitive?.content ?: ""
                            val subLang = sub["label"]?.jsonPrimitive?.content ?: "Unknown"
                            if (subPath.isNotBlank()) {
                                subtitleTracks.add(Track("$baseUrl$subPath", subLang))
                            }
                        }
                        
                        val sourceHeaders = headers.newBuilder()
                        val headersObj = source["headers"]?.jsonObject
                        headersObj?.let {
                            it["Referer"]?.jsonPrimitive?.content?.let { ref ->
                                sourceHeaders.set("Referer", ref)
                            }
                        }
                        
                        val finalVideoUrl = if (videoPath.startsWith("http")) videoPath else "$baseUrl$videoPath"
                        val displayName = "${serverName.split(":")[0].replaceFirstChar { it.uppercase() }} - $quality"
                        
                        videoList.add(
                            Video(
                                url = finalVideoUrl,
                                quality = displayName,
                                videoUrl = finalVideoUrl,
                                headers = sourceHeaders.build(),
                                subtitleTracks = subtitleTracks
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore failed server resolutions
            }
        }
        
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("pref_quality", "1080") ?: "1080"
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality, ignoreCase = true) },
                { it.quality.contains("mimi", ignoreCase = true) },
                { it.quality.contains("hls", ignoreCase = true) || it.quality.contains("m3u8", ignoreCase = true) },
            ),
        ).reversed()
    }

    // ==================== FILTERS & PREFERENCES ====================
    override fun getFilterList(): AnimeFilterList = AnimeKizzFilters.getFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "pref_quality",
            title = "Preferred quality",
            entries = listOf("1080", "720", "480", "360", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = "1080",
            summary = "%s",
        )
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
