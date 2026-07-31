package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/**
 * AnimeGG video source.
 *
 * Flow:
 *   1. GET /search/?q={title} → find anime URL
 *   2. GET {animeUrl} → find episode URL by matching epNum
 *   3. GET {episodeUrl} → extract iframe src(s) from tab-panes
 *   4. GET {iframeSrc} → regex extract `var videoSources = [...]`
 *   5. Fix unquoted JSON keys → parse → return direct .mp4/.m3u8 URLs
 *
 * No encryption, no JS execution, no tokens. Just plain HTML + regex.
 */
class AnimeGGProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AnimeGG"
    override val baseUrl = "https://www.animegg.org"

    companion object {
        private const val BASE = "https://www.animegg.org"
        private val JSON_KEY_FIX = Regex("""(?<=[{,])\s*['"]?(\w+)['"]?\s*:""")
    }

    @Serializable
    private data class GgVideo(val file: String, val label: String)

    // =================================================================
    // DEBUG HELPER
    // =================================================================
    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // =================================================================
    // STEP 1: Search → anime URL
    // =================================================================
    private suspend fun searchAnime(title: String): String? {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/search/?q=$encodedTitle"
        val doc = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()
        
        // Try exact/contains match first, fallback to first result
        val match = doc.select(".mse").firstOrNull { el ->
            val elTitle = el.selectFirst(".first h2")?.text()?.trim() ?: ""
            elTitle.equals(title, ignoreCase = true) || 
            elTitle.contains(title, ignoreCase = true) ||
            title.contains(elTitle, ignoreCase = true)
        } ?: doc.selectFirst(".mse")
        
        return match?.attr("abs:href")
    }

    // =================================================================
    // STEP 2: Anime page → episode URL
    // =================================================================
    private suspend fun getEpisodeUrl(animeUrl: String, epNum: Int): String? {
        val doc = client.newCall(GET(animeUrl, headers)).awaitSuccess().asJsoup()
        
        val episodeElement = doc.select(".newmanga li div").firstOrNull { el ->
            val text = el.selectFirst(".anm_det_pop strong")?.text() ?: ""
            val num = getEpNumber(text)
            num == epNum.toFloat()
        }
        
        return episodeElement?.selectFirst(".anm_det_pop")?.attr("abs:href")
    }

    private fun getEpNumber(input: String): Float? {
        val regex = Regex("""(\d+(\.\d+)?)(?:-\d+(\.\d+)?)?$""")
        return regex.find(input)?.groupValues?.get(1)?.toFloatOrNull()
    }

    // =================================================================
    // STEP 3 & 4: Episode page → iframes → extract videoSources
    // =================================================================
    private suspend fun extractVideos(episodeUrl: String): List<Video> {
        val doc = client.newCall(GET(episodeUrl, headers)).awaitSuccess().asJsoup()
        val iframes = doc.select(".tab-pane iframe")
        
        if (iframes.isEmpty()) return emptyList()
        
        val allVideos = mutableListOf<Video>()
        
        for (iframe in iframes) {
            val mode = when (iframe.closest(".tab-pane")?.attr("id")) {
                "subbed-Animegg" -> "[SUBBED]"
                "dubbed-Animegg" -> "[DUBBED]"
                "raw-Animegg" -> "[RAW]"
                else -> ""
            }
            
            val iframeSrc = iframe.attr("abs:src")
            if (iframeSrc.isBlank()) continue
            
            try {
                val embedDoc = client.newCall(GET(iframeSrc, headers)).awaitSuccess().asJsoup()
                val host = iframeSrc.toHttpUrlOrNull()?.host ?: ""
                
                val scriptData = embedDoc.selectFirst("script:containsData(var videoSources =)")?.data()
                    ?: continue
                    
                val rawJson = scriptData
                    .substringAfter("var videoSources = ")
                    .substringBefore(";")
                    .replace(JSON_KEY_FIX) { mr -> " \"${mr.groupValues[1]}\":" }
                    
                val videos = try {
                    rawJson.parseAs<Array<GgVideo>>()
                } catch (e: Exception) {
                    continue // Skip if JSON parsing fails
                }
                
                val videoHeaders = headers.newBuilder()
                    .add("Referer", "https://$host/")
                    .build()
                    
                for (v in videos) {
                    val url = if (v.file.startsWith("http")) v.file else "https://$host${v.file}"
                    allVideos.add(Video(url, "$name $mode ${v.label}", url, headers = videoHeaders))
                }
            } catch (_: Exception) {
                // Skip failed iframe extraction
            }
        }
        
        return allVideos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")
        
        val epNum = if (meta.epNum > 0) meta.epNum else 1
        
        // Step 1: Search
        val animeUrl = try {
            searchAnime(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '$title'")
        
        // Step 2: Get episode URL
        val episodeUrl = try {
            getEpisodeUrl(animeUrl, epNum)
        } catch (e: Exception) {
            return debugVideo("getEpisodeUrl threw: ${e.message}")
        } ?: return debugVideo("no episode $epNum found for '$title'")
        
        // Step 3 & 4: Extract videos from iframes
        val videos = try {
            extractVideos(episodeUrl)
        } catch (e: Exception) {
            return debugVideo("extractVideos threw: ${e.message}")
        }
        
        if (videos.isEmpty()) {
            return debugVideo("0 videos extracted from iframes")
        }
        
        return videos
    }
}
