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
    // STEP 1: Search by title → get show page slug (Now tries multiple titles!)
    // =================================================================
    private suspend fun searchShow(titles: List<String>): String? {
        for (title in titles) {
            if (title.isBlank()) continue
            
            // Only strip (TV), (Cour 2), etc. Keep punctuation for the API query!
            val queryTitle = title.replace(Regex("\\s*\\(.*?\\)\\s*"), "").trim()
            
            val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
                .addQueryParameter("f", "search")
                .addQueryParameter("tz", "Europe/Berlin")
                .addQueryParameter("s", queryTitle)
                .build().toString()

            val body = try {
                client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
            } catch (e: Exception) {
                continue // If this title throws an error, try the next one
            }
            
            if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
                continue
            }
            
            val jObject = try {
                json.decodeFromString<JsonObject>(body)
            } catch (e: Exception) {
                continue
            }
            
            if (jObject.isEmpty()) continue
            
            var bestMatch: String? = null
            var bestScore = -1

            for ((_, value) in jObject) {
                val entry = value.jsonObject
                val show = entry["show"]?.jsonPrimitive?.content ?: continue
                val page = entry["page"]?.jsonPrimitive?.content ?: continue
                
                // Clean both for scoring comparison only
                val showClean = show.replace(Regex("[^a-zA-Z0-9\\s]"), "").lowercase()
                val titleClean = queryTitle.replace(Regex("[^a-zA-Z0-9\\s]"), "").lowercase()
                
                var score = 0
                if (showClean == titleClean) score += 100
                else if (showClean.contains(titleClean)) score += 50
                else if (titleClean.contains(showClean)) score += 30
                
                val titleWords = titleClean.split(" ").filter { it.length > 2 }
                val showWords = showClean.split(" ").filter { it.length > 2 }
                val overlap = titleWords.count { showWords.contains(it) }
                score += overlap * 10
                
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = page
                }
            }
            
            // If we found a decent match with this title, return it immediately
            if (bestMatch != null && bestScore > 0) {
                return bestMatch
            }
        }
        return null
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
        
        if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
            return emptyList()
        }

        val jObject = json.decodeFromString<JsonObject>(body)
        val episodes = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        val matchedVideos = mutableListOf<Video>()

        for ((_, value) in episodes) {
            val epObj = value.jsonObject
            val epStr = epObj["episode"]?.jsonPrimitive?.content ?: continue
            
            val epFloat = epStr.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: continue
            
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
                break
            }
        }
        
        return matchedVideos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val titlesToTry = mutableListOf<String>()
        
        anime.title.takeIf { it.isNotBlank() }?.let { titlesToTry.add(it) }
        meta.title.takeIf { it.isNotBlank() && !titlesToTry.contains(it) }?.let { titlesToTry.add(it) }
        
        // Fetch both English and Romaji from AniList to maximize search chances
        val aniListTitles = fetchTitlesFromAniList(meta.anilistId)
        for (t in aniListTitles) {
            if (!titlesToTry.contains(t)) titlesToTry.add(t)
        }

        if (titlesToTry.isEmpty()) {
            return debugVideo("Title blank (AL: ${meta.anilistId})")
        }

        val slug = try {
            searchShow(titlesToTry)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '${titlesToTry.joinToString(" / ")}'")

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

    private suspend fun fetchTitlesFromAniList(anilistId: Int): List<String> {
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
            val titles = mutableListOf<String>()
            data.Media?.title?.english?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
            data.Media?.title?.romaji?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
            titles
        } catch (_: Exception) {
            emptyList()
        }
    }
}
