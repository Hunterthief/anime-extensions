package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        // Clean the title to match Subsplease's database format (removes punctuation and "(TV)")
        val cleanTitle = title.replace(Regex("\\s*\\(.*?\\)\\s*"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .trim()

        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "search")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("s", cleanTitle)
            .build().toString()

        val body = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        
        // 🛡️ FIX: Subsplease API returns an empty JSON array "[]" when no results are found,
        // which crashes the JsonObject parser. Handle it gracefully.
        if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
            return null
        }
        
        val jObject = json.decodeFromString<JsonObject>(body)
        
        var bestMatch: String? = null
        var bestScore = -1

        for ((_, value) in jObject) {
            val entry = value.jsonObject
            val show = entry["show"]?.jsonPrimitive?.content ?: continue
            val page = entry["page"]?.jsonPrimitive?.content ?: continue
            
            val showClean = show.replace(Regex("[^a-zA-Z0-9\\s]"), "").lowercase()
            val titleClean = cleanTitle.lowercase()
            
            var score = 0
            if (showClean == titleClean) score += 100
            else if (showClean.contains(titleClean)) score += 50
            else if (titleClean.contains(showClean)) score += 30
            
            // Word overlap bonus
            val titleWords = titleClean.split(" ").filter { it.length > 2 }
            val showWords = showClean.split(" ").filter { it.length > 2 }
            val overlap = titleWords.count { showWords.contains(it) }
            score += overlap * 10
            
            if (score > bestScore) {
                bestScore = score
                bestMatch = page
            }
        }
        
        // Fallback: return first result if scoring somehow fails
        return bestMatch ?: jObject.values.firstOrNull()?.jsonObject?.get("page")?.jsonPrimitive?.content
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
        val url = "$BASE_URL/api/?f=show&tz=Europe/Berlin&sid=$sid"
        
        val body = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        
        // 🛡️ Safety check for empty responses
        if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
            return emptyList()
        }

        val jObject = json.decodeFromString<JsonObject>(body)
        val episodes = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        val matchedVideos = mutableListOf<Video>()

        for ((_, value) in episodes) {
            val epObj = value.jsonObject
            val epStr = epObj["episode"]?.jsonPrimitive?.content ?: continue
            
            // Safely parse episode number, handling strings like "12.5" or "12v2"
            val epFloat = epStr.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: continue
            
            // Strict float comparison to prevent "12.5" from matching episode "12"
            if (epFloat == epNum.toFloat()) {
                val downloads = epObj["downloads"]?.jsonArray ?: continue
                
                for (dl in downloads) {
                    val dlObj = dl.jsonObject
                    val res = dlObj["res"]?.jsonPrimitive?.content ?: continue
                    val magnet = dlObj["magnet"]?.jsonPrimitive?.content ?: continue
                    
                    if (magnet.startsWith("magnet:")) {
                        matchedVideos.add(Video(magnet, "$name ${res}p", magnet))
                    }
                }
                break // Found the exact episode, no need to continue
            }
        }
        
        return matchedVideos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        var title = anime.title.ifBlank { meta.title }
        
        // Fallback to AniList if the title is somehow blank
        if (title.isBlank()) {
            title = fetchTitleFromAniList(meta.anilistId) ?: return debugVideo("Title blank (AL: ${meta.anilistId})")
        }

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

    // ==================== AniList Title Fetcher ====================
    @Serializable private data class AniListMediaResponse(val Media: AniListMediaFull? = null)
    @Serializable private data class AniListMediaFull(val title: AniListTitlesFull? = null)
    @Serializable private data class AniListTitlesFull(val english: String? = null, val romaji: String? = null)

    private suspend fun fetchTitleFromAniList(anilistId: Int): String? {
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject { put("id", anilistId) }
        return try {
            val request = graphQLPost("https://graphql.anilist.co", headers, query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (_: Exception) {
            null
        }
    }
}
