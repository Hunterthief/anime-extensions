package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.KickAssAnimeExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.ServersDto
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class KickAssAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {
    override val name = "KickAssAnime"
    override val baseUrl = "https://kaa.lt"
    private val apiUrl = "$baseUrl/api/show"
    
    private val kaaHeaders: Headers
        get() = headers.newBuilder().set("Referer", "$baseUrl/").build()

    private val extractor by lazy { KickAssAnimeExtractor(client, kaaHeaders) }
    private val animeCache = ConcurrentHashMap<Int, String>()

    // ==================== Search & Matching Logic ====================

    // FIX: Changed to camelCase to satisfy ktlint
    private val seasonNumberRegex = Regex(
        """(?:season|part)\s*(\d+)|(\d+)(?:st|nd|rd|th)\s*(?:season|part)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractSeasonNumber(text: String): Int? {
        val match = seasonNumberRegex.find(text) ?: return null
        val numStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return numStr.toIntOrNull()
    }

    private fun stripSeasonInfo(title: String): String {
        return title
            .replace(Regex("""\s*[-:]\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+(?:st|nd|rd|th)\s*(?:season|part).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun normalizeTitle(raw: String): String = raw
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

    private suspend fun findAnimeSlug(anilistId: Int, title: String): String? {
        animeCache[anilistId]?.let { return it }

        val cleanTitle = title.trim().lowercase()
        val querySeasonNumber = extractSeasonNumber(cleanTitle)
        val baseTitle = stripSeasonInfo(title)

        // Strategy 1: Full title
        var slug = searchApiForSlug(normalizeTitle(title), querySeasonNumber, title)
        if (slug != null) {
            animeCache[anilistId] = slug
            return slug
        }

        // Strategy 2: Base title
        if (querySeasonNumber != null && baseTitle != title) {
            slug = searchApiForSlug(normalizeTitle(baseTitle), querySeasonNumber, baseTitle)
            if (slug != null) {
                animeCache[anilistId] = slug
                return slug
            }
        }

        // Strategy 3: Romaji
        val romajiTitle = fetchTitleFromAniList(anilistId, preferRomaji = true)
        if (romajiTitle != null && romajiTitle.lowercase() != cleanTitle) {
            val romajiSeason = extractSeasonNumber(romajiTitle.lowercase())
            slug = searchApiForSlug(normalizeTitle(romajiTitle), romajiSeason, romajiTitle)
            if (slug != null) {
                animeCache[anilistId] = slug
                return slug
            }
        }

        return null
    }

    private suspend fun searchApiForSlug(normalizedTitle: String, querySeasonNumber: Int?, originalTitle: String): String? {
        val searchUrl = "$baseUrl/api/fsearch"
        val searchHeaders = kaaHeaders.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Content-Type", "application/json")
            .build()
            
        val body = buildJsonObject {
            put("page", 1)
            put("query", originalTitle)
        }.toJsonRequestBody()
        
        val req = POST(searchUrl, headers = searchHeaders, body = body)
        val resp = client.newCall(req).awaitSuccess()
        val results = resp.parseAs<SearchResponseDto>()
        
        if (results.result.isEmpty()) return null

        // 1. Exact match
        var best = results.result.firstOrNull {
            normalizeTitle(it.title) == normalizedTitle || 
            normalizeTitle(it.title_en ?: "") == normalizedTitle
        }

        // 2. Season match
        if (best == null && querySeasonNumber != null) {
            best = results.result.firstOrNull {
                extractSeasonNumber(it.title) == querySeasonNumber ||
                extractSeasonNumber(it.title_en ?: "") == querySeasonNumber
            }
        }

        // 3. Base show match
        if (best == null && querySeasonNumber == null) {
            best = results.result.firstOrNull {
                extractSeasonNumber(it.title) == null &&
                extractSeasonNumber(it.title_en ?: "") == null
            }
        }

        // 4. Fallback: shortest contains match
        if (best == null) {
            best = results.result.minByOrNull {
                val n = it.title.lowercase().trim()
                when {
                    n.startsWith(normalizedTitle) -> n.length
                    normalizedTitle.startsWith(n) -> n.length + 1000
                    n.contains(normalizedTitle) -> n.length + 2000
                    else -> Int.MAX_VALUE
                }
            }
        }
        
        return best?.slug
    }

    // ==================== Core Flow ====================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(60)}")
        }

        val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
        
        if (title.isBlank()) {
            val anilistTitle = fetchTitleFromAniList(meta.anilistId, preferRomaji = false)
            if (anilistTitle.isNullOrBlank()) return dbg("title blank (AL: ${meta.anilistId})")
            return fetchVideosWithTitle(anilistTitle, meta)
        }
        
        return fetchVideosWithTitle(title, meta)
    }

    private suspend fun fetchVideosWithTitle(title: String, meta: EpisodeMeta): List<Video> {
        val slug = try {
            findAnimeSlug(meta.anilistId, title)
        } catch (e: Exception) {
            return dbg("SEARCH ERR: ${e.message?.take(60)}")
        } ?: return dbg("0 results for '${title.take(30)}'")
        
        val epUrl = try {
            findEpisodeUrl(slug, meta.epNum)
        } catch (e: Exception) {
            return dbg("EPISODE ERR: ${e.message?.take(60)}")
        } ?: return dbg("0 episodes found for ep ${meta.epNum}")
        
        val videoResp = try {
            client.newCall(GET("$apiUrl$epUrl", kaaHeaders)).awaitSuccess()
        } catch (e: Exception) {
            return dbg("VIDEO RESP ERR: ${e.message?.take(60)}")
        }
        
        val servers = try {
            videoResp.parseAs<ServersDto>()
        } catch (e: Exception) {
            return dbg("PARSE ERR: ${e.message?.take(60)}")
        }
        
        if (servers.servers.isEmpty()) return dbg("0 servers found in response")

        val hosterExclusion = preferences.getStringSet("kaa_hoster_exclusion", emptySet()) ?: emptySet()
        
        val videos = servers.servers.parallelCatchingFlatMap { server ->
            if (hosterExclusion.contains(server.name)) return@parallelCatchingFlatMap emptyList()
            extractor.videosFromUrl(server.src, server.name)
        }
        
        if (videos.isEmpty()) return dbg("Extractor returned 0 videos")
        
        return videos
    }

    private suspend fun findEpisodeUrl(slug: String, epNum: Int): String? {
        val lang = "en-US"
        for (page in 1..3) {
            val epResp = client.newCall(GET("$apiUrl/$slug/episodes?page=$page&lang=$lang", kaaHeaders)).awaitSuccess()
            val epData = epResp.parseAs<EpisodeResponseDto>()
            
            val ep = epData.result.firstOrNull { 
                it.episode_string.toFloatOrNull()?.toInt() == epNum 
            } ?: epData.result.firstOrNull {
                it.episode_string == epNum.toString()
            }
            
            if (ep != null) return "/$slug/episode/ep-${ep.episode_string}-${ep.slug}"
            if (epData.result.isEmpty()) break
        }
        return null
    }

    // ==================== AniList Fallback ====================

    @Serializable private data class AniListMediaResponse(val Media: AniListMediaFull? = null)
    @Serializable private data class AniListMediaFull(val title: AniListTitlesFull? = null)
    @Serializable private data class AniListTitlesFull(val english: String? = null, val romaji: String? = null)

    private suspend fun fetchTitleFromAniList(anilistId: Int, preferRomaji: Boolean): String? {
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()

        val variables = buildJsonObject { put("id", anilistId) }

        return try {
            val request = graphQLPost("https://graphql.anilist.co", kaaHeaders, query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            if (preferRomaji) data.Media?.title?.romaji ?: data.Media?.title?.english
            else data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (_: Exception) { null }
    }

    private fun dbg(msg: String): List<Video> = listOf(Video("debug://x", msg.take(120), "debug://x"))
}
