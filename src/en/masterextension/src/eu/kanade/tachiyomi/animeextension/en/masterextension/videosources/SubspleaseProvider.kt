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
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", BASE_URL)
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
    // STEP 1: Search — mirrors source extension EXACTLY
    // Source: GET("$baseUrl/api/?f=search&tz=Europe/Berlin&s=$query")
    // No title cleaning, no stripping. Raw query passed directly.
    // =================================================================
    private suspend fun searchShow(titles: List<String>): String? {
        for (title in titles) {
            if (title.isBlank()) continue

            // Mirror source EXACTLY: raw string URL, no cleaning
            val url = "$BASE_URL/api/?f=search&tz=Europe/Berlin&s=$title"

            val body = try {
                client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
            } catch (_: Exception) {
                continue
            }

            if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
                continue
            }

            val jObject = try {
                json.decodeFromString<JsonObject>(body)
            } catch (_: Exception) {
                continue
            }

            if (jObject.isEmpty()) continue

            // Mirror source parsing: jObject.entries → value.jsonObject["show"] / ["page"]
            var bestMatch: String? = null
            var bestScore = -1

            for ((_, value) in jObject) {
                val itJ = value.jsonObject
                val show = itJ["show"]?.jsonPrimitive?.content ?: continue
                val page = itJ["page"]?.jsonPrimitive?.content ?: continue

                // Score for auto-matching (source doesn't need this because user picks manually)
                val showClean = show.lowercase().trim()
                val titleClean = title.lowercase().trim()

                var score = 0
                if (showClean == titleClean) {
                    score += 1000
                } else if (showClean.contains(titleClean)) {
                    score += 500
                } else if (titleClean.contains(showClean)) {
                    score += 300
                }

                // Word overlap
                val titleWords = titleClean.split(" ").filter { it.length > 2 }
                val showWords = showClean.split(" ").filter { it.length > 2 }
                val overlap = titleWords.count { showWords.contains(it) }
                score += overlap * 20

                if (score > bestScore) {
                    bestScore = score
                    bestMatch = page
                }
            }

            if (bestMatch != null && bestScore > 0) {
                return bestMatch
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Fetch show page → extract sid
    // Mirror source: document.select("#show-release-table").attr("sid")
    // =================================================================
    private suspend fun getShowId(slug: String): String? {
        val url = "$BASE_URL/shows/$slug"
        val html = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(html)
        val sid = doc.select("#show-release-table").attr("sid")
        return sid.takeIf { it.isNotBlank() }
    }

    // =================================================================
    // STEP 3: Episode API → magnet links
    // Mirror source: GET("$baseUrl/api/?f=show&tz=Europe/Berlin&sid=$sId")
    // Then parse jObject["episode"]?.jsonObject?.entries
    // Match by: if (num != epN) return@mapNotNull null
    // =================================================================
    private suspend fun getMagnets(sid: String, epNum: Int): List<Video> {
        val url = "$BASE_URL/api/?f=show&tz=Europe/Berlin&sid=$sid"

        val body = client.newCall(GET(url, siteHeaders)).awaitSuccess().bodyString()

        if (body.isBlank() || body.trim() == "[]" || body.trim() == "{}") {
            return emptyList()
        }

        val jObject = json.decodeFromString<JsonObject>(body)
        val epE = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        // Mirror source: match by string comparison like the source does
        // Source: val num = itJ["episode"]?.jsonPrimitive?.content
        //         if (num != epN) return@mapNotNull null
        val targetNum = epNum.toString()

        return epE.mapNotNull { (_, value) ->
            val itJ = value.jsonObject
            val epN = itJ["episode"]?.jsonPrimitive?.content ?: return@mapNotNull null

            // Match: source uses exact string match (num != epN)
            // We need to handle "1" matching "1" and "12" matching "12"
            val epFloat = epN.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: return@mapNotNull null
            if (epFloat.toInt() != epNum) return@mapNotNull null

            // Mirror source: itJ["downloads"]?.jsonArray?.mapNotNull
            itJ["downloads"]?.jsonArray?.mapNotNull inner@{ item ->
                val quality = item.jsonObject["res"]?.jsonPrimitive?.content?.plus("p") ?: return@inner null
                val videoUrl = item.jsonObject["magnet"]?.jsonPrimitive?.content ?: return@inner null

                if (!videoUrl.startsWith("magnet:")) return@inner null

                // Mirror source: Video(videoUrl, quality, videoUrl)
                Video(videoUrl, "$name $quality", videoUrl)
            }
        }.flatten()
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val titlesToTry = mutableListOf<String>()

        anime.title.takeIf { it.isNotBlank() }?.let { titlesToTry.add(it) }
        meta.title.takeIf { it.isNotBlank() && !titlesToTry.contains(it) }?.let { titlesToTry.add(it) }

        // Fetch both English and Romaji from AniList
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
            return debugVideo("no magnets for sid '$sid' ep ${meta.epNum}")
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
