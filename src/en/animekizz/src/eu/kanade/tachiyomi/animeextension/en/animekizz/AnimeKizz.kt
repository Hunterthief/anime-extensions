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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // ==================== POPULAR ====================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/catalog?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    // ==================== LATEST ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/catalog?sort=recently_updated&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    // ==================== SEARCH ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.firstInstanceOrNull<AnimeKizzFilters.GenreFilter>()
        val genre = genreFilter?.toUriPart() ?: ""
        val genreQuery = if (genre.isNotEmpty()) "&genre=$genre" else ""
        
        return GET("$baseUrl/catalog?q=${query.encodeUri()}$genreQuery&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        return parseAnimeList(document)
    }

    // ==================== SHARED PARSER ====================
    private fun parseAnimeList(document: Document): AnimesPage {
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
        
        val hasNextPage = document.select("a[href*='page=2'], a:contains(Next), button:contains(Next)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ==================== DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.select("h1, h2.text-2xl, h2.text-3xl").firstOrNull()?.text()?.trim() ?: document.title()
            
            // Cover image (prioritize /cover/ paths to avoid wide banners)
            thumbnail_url = document.select("img[src*='/cover/']").firstOrNull()?.attr("abs:src")
                ?: document.select("img").firstOrNull { it.attr("src").contains("anilist.co", ignoreCase = true) }?.attr("abs:src")
                ?: document.select("img").firstOrNull()?.attr("abs:src")
            
            genre = document.select("a[href*='/catalog?genre=']").joinToString { it.text() }
            
            val synopsis = document.select("p.line-clamp-4, .synopsis, div.synopsis").firstOrNull()?.text()?.trim() ?: ""
            
            // Extract metadata blocks
            val metaBlocks = document.select("div.flex.flex-col.gap-1, div.grid > div, .metadata-item")
            
            fun getMeta(label: String): String {
                return metaBlocks.firstOrNull { 
                    it.selectFirst("span")?.text()?.contains(label, ignoreCase = true) == true 
                }?.select("span.font-bold, span.text-sm, span")?.lastOrNull()?.text()?.trim() 
                  ?: metaBlocks.firstOrNull { 
                      it.text().contains(label, ignoreCase = true) 
                  }?.select("span")?.lastOrNull()?.text()?.trim() ?: ""
            }
            
            // Extract top badges (Type, Status, Score)
            val badgeContainer = document.selectFirst("div.flex.items-center.gap-3.mb-2.flex-wrap")
            val type = badgeContainer?.select("span")?.getOrNull(0)?.text()?.trim() ?: ""
            val statusBadge = badgeContainer?.select("span")?.getOrNull(1)?.text()?.trim() ?: ""
            val score = badgeContainer?.select("span")?.getOrNull(2)?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            
            val studio = getMeta("Studio")
            val episodes = getMeta("Episodes")
            val season = getMeta("Season")
            val rating = getMeta("Rating")
            val duration = getMeta("Duration") ?: getMeta("Average Length") ?: getMeta("Length") ?: getMeta("Avg Length")
            
            // Set Studio to the author field as requested
            author = studio
            
            // Set Status field
            status = when {
                statusBadge.contains("Airing", ignoreCase = true) || statusBadge.contains("Releasing", ignoreCase = true) -> SAnime.ONGOING
                statusBadge.contains("Completed", ignoreCase = true) || statusBadge.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            
            // Build a clean, formatted description with metadata ALWAYS at the end
            val descBuilder = StringBuilder()
            if (synopsis.isNotBlank()) {
                descBuilder.append(synopsis).append("\n\n")
            }
            
            val metaLines = mutableListOf<String>()
            if (type.isNotBlank()) metaLines.add("Type: $type")
            if (statusBadge.isNotBlank()) metaLines.add("Status: $statusBadge")
            if (score.isNotBlank()) metaLines.add("Score: $score")
            if (season.isNotBlank()) metaLines.add("Aired: $season")
            if (duration.isNotBlank()) metaLines.add("Duration: $duration")
            if (episodes.isNotBlank()) metaLines.add("Episodes: $episodes")
            if (rating.isNotBlank()) metaLines.add("Rating: $rating")
            
            if (metaLines.isNotEmpty()) {
                descBuilder.append(metaLines.joinToString("\n"))
            }
            
            description = descBuilder.toString().trim()
        }
    }

    // ==================== EPISODES ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        return document.select("a[href*='/watch/']").mapNotNull { element ->
            val href = element.attr("href")
            if (!href.contains("/watch/")) return@mapNotNull null
            
            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                
                // Extract episode number directly from the URL (e.g., "episode-14")
                val epMatch = Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)
                val epNum = epMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                episode_number = epNum
                
                val title = element.select("span.truncate, span.block").firstOrNull()?.text()?.trim() 
                    ?: element.select("button[aria-label*='episode']").text().replace(Regex("EP\\s*", RegexOption.IGNORE_CASE), "").trim()
                
                name = if (title.isNotBlank() && !title.startsWith("Episode", ignoreCase = true)) {
                    "Episode ${epNum.toInt()}: $title"
                } else {
                    "Episode ${epNum.toInt()}"
                }
                
                date_upload = 0L
            }
        }.distinctBy { it.url }.sortedBy { it.episode_number }
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
                // Ignore failed server resolutions and try the next one
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
