package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Subsplease video source — magnet-link based.
 *
 * Subsplease provides torrent magnet links directly via a JSON API.
 * Aniyomi's built-in libtorrent engine handles magnet: URIs natively,
 * so no extraction, decryption, or m3u8 parsing is needed.
 */
class SubspleaseProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Subsplease"
    override val baseUrl = "https://subsplease.org"

    companion object {
        private const val BASE_URL = "https://subsplease.org"
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val siteHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()
    }

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
    // STEP 1: Search by title → get show page slug
    // =================================================================
    private suspend fun searchShow(title: String): String? {
        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "search")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("s", title)
            .build().toString()

        val body = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        val jObject = json.decodeFromString<JsonObject>(body)
        
        for ((_, value) in jObject) {
            val entry = value.jsonObject
            val show = entry["show"]?.jsonPrimitive?.content ?: continue
            val page = entry["page"]?.jsonPrimitive?.content ?: continue
            
            // Match by title (case-insensitive)
            if (show.equals(title, ignoreCase = true) ||
                title.contains(show, ignoreCase = true) ||
                show.contains(title, ignoreCase = true)
            ) {
                return page
            }
        }
        
        // Fallback: return first result
        return jObject.values.firstOrNull()?.jsonObject?.get("page")?.jsonPrimitive?.content
    }

    // =================================================================
    // STEP 2: Fetch show page → extract sid
    // =================================================================
    private suspend fun getShowId(slug: String): String? {
        val url = "$BASE_URL/shows/$slug"
        val html = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(html)
        val sid = doc.selectFirst("#show-release-table")?.attr("sid")
        return sid?.takeIf { it.isNotBlank() }
    }

    // =================================================================
    // STEP 3: Episode API → magnet links
    // =================================================================
    private suspend fun getMagnets(sid: String, epNum: Int): List<Video> {
        // FIX: Exact URL pattern from the original working extension. 
        // Do NOT pass "num" to the API. The API returns the full list, we filter locally.
        val url = "$BASE_URL/api/?f=show&tz=Europe/Berlin&sid=$sid"
        
        val body = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        val jObject = json.decodeFromString<JsonObject>(body)
        val episodes = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        var bestMatchVideos = emptyList<Video>()
        var isExactMatch = false

        for ((_, value) in episodes) {
            val epObj = value.jsonObject
            val epStr = epObj["episode"]?.jsonPrimitive?.content ?: continue
            
            val epFloat = epStr.toFloatOrNull() ?: continue
            val isCurrentExact = (epFloat == epNum.toFloat())
            
            // If we already found an exact match, skip non-exact matches (e.g., skip "1.5" if we want "1")
            if (isExactMatch && !isCurrentExact) continue
            
            if (epFloat.toInt() == epNum) {
                val downloads = epObj["downloads"]?.jsonArray ?: continue
                val currentVideos = mutableListOf<Video>()
                
                for (dl in downloads) {
                    val dlObj = dl.jsonObject
                    val res = dlObj["res"]?.jsonPrimitive?.content ?: continue
                    val magnet = dlObj["magnet"]?.jsonPrimitive?.content ?: continue
                    
                    if (magnet.startsWith("magnet:")) {
                        currentVideos.add(Video(magnet, "$name ${res}p", magnet))
                    }
                }
                
                if (currentVideos.isNotEmpty()) {
                    bestMatchVideos = currentVideos
                    isExactMatch = isCurrentExact
                    if (isExactMatch) break // Found exact match, no need to look further
                }
            }
        }
        
        return bestMatchVideos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val slug = try {
            searchShow(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '$title'")

        val sid = try {
            getShowId(slug)
        } catch (e: Exception) {
            return debugVideo("getShowId threw: ${e.message}")
        } ?: return debugVideo("getShowId null for slug '$slug'")

        val videos = try {
            getMagnets(sid, meta.epNum)
        } catch (e: Exception) {
            return debugVideo("getMagnets threw: ${e.message}")
        }

        if (videos.isEmpty()) {
            return debugVideo("no magnets found for sid '$sid' ep ${meta.epNum}")
        }

        return videos
    }
}
