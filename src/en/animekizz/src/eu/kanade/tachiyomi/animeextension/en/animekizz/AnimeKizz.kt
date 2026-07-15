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
            
            // Helper to extract metadata values cleanly
            fun getMeta(label: String): String {
                return document.select("div.flex.flex-col.gap-1").firstOrNull { 
                    it.select("span").firstOrNull()?.text()?.contains(label, ignoreCase = true) == true 
                }?.select("span")?.lastOrNull()?.text()?.trim()?.replace(Regex("\\s+"), " ") ?: ""
            }
            
            // Extract Type, Status, and Score from the top badge container
            val badgeContainer = document.select("div.flex.items-center.gap-3.mb-2.flex-wrap > span")
            val type = badgeContainer.firstOrNull { 
                it.text().trim().uppercase() in listOf("TV", "MOVIE", "OVA", "ONA", "SPECIAL", "MUSIC") 
            }?.text()?.trim() ?: ""
            
            val status = badgeContainer.firstOrNull { 
                it.text().trim().lowercase() in listOf("airing", "finished", "completed", "not yet aired", "cancelled") 
            }?.text()?.trim() ?: ""
            
            val score = badgeContainer.firstOrNull { 
                it.select("svg.lucide-star").isNotEmpty() 
            }?.text()?.trim() ?: ""
            
            val season = getMeta("Season")
            val episodes = getMeta("Episodes")
            val studio = getMeta("Studio")
            val source = getMeta("Source")
            
            // Set dedicated fields
            author = studio
            this.status = when {
                status.contains("Airing", ignoreCase = true) || status.contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
                status.contains("Completed", ignoreCase = true) || status.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            
            // Build clean, formatted description
            val descBuilder = StringBuilder()
            if (synopsis.isNotBlank()) {
                descBuilder.append(synopsis).append("\n\n")
            }
            
            val metaLines = mutableListOf<String>()
            if (type.isNotBlank()) metaLines.add("Type: $type")
            if (status.isNotBlank()) metaLines.add("Status: $status")
            if (score.isNotBlank()) metaLines.add("Score: $score")
            if (season.isNotBlank()) metaLines.add("Aired: $season")
            if (episodes.isNotBlank()) metaLines.add("Episodes: $episodes")
            if (studio.isNotBlank()) metaLines.add("Studio: $studio")
            if (source.isNotBlank()) metaLines.add("Source: $source")
            
            if (metaLines.isNotEmpty()) {
                descBuilder.append(metaLines.joinToString("\n"))
            }
            
            description = descBuilder.toString().trim()
        }
    }

    // ==================== EPISODES (ADAPTIVE BATCH SCRAPING) ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        
        // 1. Get total episodes from metadata to know when to stop
        val episodesCountStr = response.useAsJsoup().select("div.flex.flex-col.gap-1").firstOrNull { 
            it.select("span").firstOrNull()?.text()?.contains("Episodes", ignoreCase = true) == true 
        }?.select("span")?.lastOrNull()?.text()?.trim()?.replace(Regex("\\D"), "")
        
        val totalEps = episodesCountStr?.toIntOrNull() ?: 12
        
        // 2. Get slug from the current details page URL
        val slug = response.request.url.toString()
            .substringAfter("/anime/")
            .substringBefore("?")
            .trim()
        
        var currentEp = 1
        val maxIterations = 100 // Safety break to prevent infinite loops
        
        for (iteration in 1..maxIterations) {
            if (currentEp > totalEps) break
            
            val watchUrl = "$baseUrl/watch/$slug-episode-$currentEp"
            try {
                val watchResponse = client.newCall(GET(watchUrl, headers)).execute()
                val watchDoc = watchResponse.useAsJsoup()
                
                var maxEpFoundOnPage = 0
                var validEpisodesFound = 0
                
                // Select ONLY <a> tags for episodes
                val pageEpisodes = watchDoc.select("a[href^='/watch/']").mapNotNull { element ->
                    val href = element.attr("href")
                    if (!href.contains("/watch/")) return@mapNotNull null
                    
                    val epMatch = Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)
                    val epNum = epMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    
                    if (epNum > 0) {
                        maxEpFoundOnPage = maxOf(maxEpFoundOnPage, epNum.toInt())
                    }
                    
                    // FIX: Bulletproof title extraction. 
                    // The title is always in a <span> with the "truncate" class inside the episode card.
                    // This completely ignores the description <p> tag and any ad <div>s.
                    val title = element.selectFirst("span.truncate")?.text()?.trim()
                        ?: "Episode ${epNum.toInt()}"
                    
                    validEpisodesFound++
                    
                    SEpisode.create().apply {
                        setUrlWithoutDomain(href)
                        episode_number = epNum
                        name = if (title.isNotBlank() && !title.startsWith("Episode", ignoreCase = true) && !title.startsWith("Ep", ignoreCase = true)) {
                            "Episode ${epNum.toInt()}: $title"
                        } else {
                            title.ifBlank { "Episode ${epNum.toInt()}" }
                        }
                        date_upload = 0L
                    }
                }
                
                episodes.addAll(pageEpisodes)
                
                // ADAPTIVE LOGIC: Jump to the episode immediately following the highest one found on this page.
                if (validEpisodesFound > 0 && maxEpFoundOnPage > 0) {
                    currentEp = maxEpFoundOnPage + 1
                } else {
                    break
                }
                
            } catch (e: Exception) {
                break
            }
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
        
        // Detect active sub type (Hard Sub, Soft Sub, or Dub)
        val activeSubTypeButton = document.selectFirst("div[data-slot='toggle-group'] button[aria-pressed='true']")
        val subTypeText = activeSubTypeButton?.selectFirst("span")?.text()?.trim() ?: "Soft Sub"
        val apiSubType = if (subTypeText.equals("Dub", ignoreCase = true)) "dub" else "sub"
        
        // Extract available servers dynamically
        val availableServers = document.select("button[data-slot='button'] span.truncate")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        
        val serversToTry = if (availableServers.isNotEmpty()) {
            availableServers.map { "$it:$apiSubType" }
        } else {
            listOf("mimi:sub", "yuki:sub", "sora:sub", "beep:sub", "uwu:sub", "kiwi:sub", "mimi:dub", "yuki:dub")
        }
        
        serversToTry.forEach { serverId ->
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
                        val displayName = "${serverName.split(":")[0].replaceFirstChar { it.uppercase() }} - $quality ($subTypeText)"
                        
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
