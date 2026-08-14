package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.ANIMEPAHE_UA
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.AnimePaheHlsServer
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.DdosGuardInterceptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.KwikExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.PaheEpisodeDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.PaheResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.PaheSearchResultDto
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "AnimePahe"
    
    override val baseUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            return if (stored != null && stored in PREF_DOMAIN_VALUES) {
                stored
            } else {
                preferences.edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
                PREF_DOMAIN_DEFAULT
            }
        }

    private val cfBypassUserAgent: String
        get() = preferences.getString(PREF_CF_UA_KEY, ANIMEPAHE_UA)?.takeIf { it.isNotBlank() } ?: ANIMEPAHE_UA

    private val cleanClient: OkHttpClient by lazy {
        client.newBuilder().apply { networkInterceptors().clear() }.build()
    }

    private val paheClient: OkHttpClient by lazy {
        cleanClient.newBuilder()
            .addInterceptor(DdosGuardInterceptor(cleanClient) { cfBypassUserAgent })
            .build()
    }

    private val kwikClient: OkHttpClient by lazy {
        paheClient.newBuilder().apply { interceptors().removeAll { it is DdosGuardInterceptor } }.build()
    }

    private val paheHeaders: Headers by lazy {
        headers.newBuilder().set("Referer", "$baseUrl/").build()
    }

    private val sessionCache = ConcurrentHashMap<Int, String>()

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

    private fun normalizeSearchQuery(raw: String): String = raw
        .replace(Regex("[^a-zA-Z0-9\\s]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTitle(raw: String): String = raw
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

    private suspend fun findAnimeSession(anilistId: Int, title: String): String? {
        sessionCache[anilistId]?.let { return it }

        val cleanTitle = title.trim().lowercase()
        val querySeasonNumber = extractSeasonNumber(cleanTitle)
        val baseTitle = stripSeasonInfo(title)

        // Strategy 1: Full title
        var result = searchApiForSession(normalizeTitle(title), querySeasonNumber, title)
        if (result != null) {
            sessionCache[anilistId] = result
            return result
        }

        // Strategy 2: Base title (strips "Season 2" to get all entries, then matches season number)
        if (querySeasonNumber != null && baseTitle != title) {
            result = searchApiForSession(normalizeTitle(baseTitle), querySeasonNumber, baseTitle)
            if (result != null) {
                sessionCache[anilistId] = result
                return result
            }
        }

        // Strategy 3: Romaji fallback
        val romajiTitle = fetchTitleFromAniList(anilistId, preferRomaji = true)
        if (romajiTitle != null && romajiTitle.lowercase() != cleanTitle) {
            val romajiSeason = extractSeasonNumber(romajiTitle.lowercase())
            result = searchApiForSession(normalizeTitle(romajiTitle), romajiSeason, romajiTitle)
            if (result != null) {
                sessionCache[anilistId] = result
                return result
            }
        }

        return null
    }

    private suspend fun searchApiForSession(normalizedTitle: String, querySeasonNumber: Int?, originalTitle: String): String? {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("m", "search")
            addQueryParameter("q", normalizeSearchQuery(originalTitle))
        }.build()

        val response = paheClient.newCall(GET(searchUrl, paheHeaders)).await()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code} from search API")
        }

        val result = response.parseAs<PaheResponseDto<PaheSearchResultDto>>()
        if (result.items.isEmpty()) return null

        // 1. Exact match
        var matched = result.items.firstOrNull { normalizeTitle(it.title) == normalizedTitle }

        // 2. Season match
        if (matched == null && querySeasonNumber != null) {
            matched = result.items.firstOrNull {
                extractSeasonNumber(normalizeTitle(it.title)) == querySeasonNumber
            }
        }

        // 3. Base show match (no season number)
        if (matched == null && querySeasonNumber == null) {
            matched = result.items.firstOrNull {
                extractSeasonNumber(normalizeTitle(it.title)) == null
            }
        }

        return matched?.session ?: result.items.firstOrNull()?.session
    }

    // ==================== Core Flow ====================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
            
            if (title.isBlank()) {
                val anilistTitle = fetchTitleFromAniList(meta.anilistId, preferRomaji = false)
                if (anilistTitle.isNullOrBlank()) return dbg("title blank (AL: ${meta.anilistId})")
                return fetchVideosWithTitle(anilistTitle, meta)
            }
            return fetchVideosWithTitle(title, meta)
        } catch (e: Throwable) {
            dbg("FATAL: ${e::class.simpleName}: ${e.message?.take(60)}")
        }
    }

    private suspend fun fetchVideosWithTitle(title: String, meta: EpisodeMeta): List<Video> {
        val animeSession = findAnimeSession(meta.anilistId, title)
            ?: return dbg("0 results for '${title.take(30)}' on $baseUrl")

        val episodeSession = fetchEpisodeSession(animeSession, meta.epNum)
            ?: return dbg("0 episodes found for ep ${meta.epNum}")

        val videos = extractVideos(animeSession, episodeSession)
        if (videos.isEmpty()) return dbg("Extractor returned 0 videos")
        return videos
    }

    private suspend fun fetchEpisodeSession(animeSession: String, epNum: Int): String? {
        var page = 1
        val allEpisodes = mutableListOf<PaheEpisodeDto>()
        
        while (true) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("m", "release")
                addQueryParameter("id", animeSession)
                addQueryParameter("sort", "episode_asc")
                addQueryParameter("page", page.toString())
            }.build()

            val response = paheClient.newCall(GET(url, paheHeaders)).await()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ${response.code} from episode API")
            }

            val episodesData = response.parseAs<PaheResponseDto<PaheEpisodeDto>>()
            allEpisodes.addAll(episodesData.items)

            // 1. Try exact match first
            val exactMatch = episodesData.items.firstOrNull { abs(it.episodeNumber - epNum.toFloat()) < 0.001f }
            if (exactMatch != null) return exactMatch.session

            if (page >= episodesData.lastPage) break
            page++
        }
        
        // 2. FALLBACK: Absolute vs Relative numbering mismatch
        // If AniList says "Episode 1" but the site lists it as "Episode 29" (continuing from S1),
        // we just grab the Nth episode from the sorted list.
        if (epNum > 0 && epNum <= allEpisodes.size) {
            val sorted = allEpisodes.sortedBy { it.episodeNumber }
            return sorted[epNum - 1].session
        }
        
        return null
    }

    private suspend fun extractVideos(animeSession: String, episodeSession: String): List<Video> {
        val response = paheClient.newCall(
            GET("$baseUrl/play/$animeSession/$episodeSession", paheHeaders),
        ).awaitSuccess()

        val document = response.useAsJsoup()

        val downloadLinks = document.select("div#pickDownload > a")
        val links = document.select("div#resolutionMenu > button").withIndex().map { (index, btn) ->
            Triple(btn.attr("data-src"), downloadLinks.getOrNull(index)?.attr("href"), btn.text())
        }

        if (links.isEmpty()) return emptyList()

        val useHLS = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)

        val videos = if (!useHLS) {
            val mp4Videos = links.mapNotNull { (_, paheWinLink, quality) ->
                if (paheWinLink.isNullOrBlank()) return@mapNotNull null
                try {
                    KwikExtractor(paheClient, paheHeaders, cfBypassUserAgent).getStreamVideo(paheWinLink, quality)
                } catch (e: Throwable) { null }
            }
            AnimePaheHlsServer.processMp4VideoList(paheClient, mp4Videos)
        } else { emptyList() }

        return videos.ifEmpty {
            val hlsVideos = links.mapNotNull { (kwikLink, _, quality) ->
                try {
                    KwikExtractor(kwikClient, paheHeaders, cfBypassUserAgent).getHlsVideo(kwikLink, referer = "$baseUrl/", quality = "$quality (HLS)")
                } catch (e: Throwable) { null }
            }
            AnimePaheHlsServer.processVideoList(kwikClient, hlsVideos)
        }
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
            val request = graphQLPost("https://graphql.anilist.co", paheHeaders, query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            if (preferRomaji) data.Media?.title?.romaji ?: data.Media?.title?.english
            else data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (_: Exception) { null }
    }

    private fun dbg(msg: String): List<Video> = listOf(Video("debug://x", msg.take(120), "debug://x"))

    companion object {
        private const val PREF_DOMAIN_KEY = "animepahe_preferred_domain"
        private val PREF_DOMAIN_VALUES = arrayOf("https://animepahe.pw", "https://animepahe.com", "https://animepahe.org")
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.pw"
        private const val PREF_LINK_TYPE_KEY = "animepahe_preferred_link_type"
        private const val PREF_LINK_TYPE_DEFAULT = true
        private const val PREF_CF_UA_KEY = "animepahe_cf_bypass_ua"
    }
}
