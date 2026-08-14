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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
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
        client.newBuilder()
            .apply { networkInterceptors().clear() }
            .build()
    }

    private val paheClient: OkHttpClient by lazy {
        cleanClient.newBuilder()
            .addInterceptor(DdosGuardInterceptor(cleanClient) { cfBypassUserAgent })
            .build()
    }

    private val kwikClient: OkHttpClient by lazy {
        paheClient.newBuilder()
            .apply { interceptors().removeAll { it is DdosGuardInterceptor } }
            .build()
    }

    private val paheHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
    }

    private val sessionCache = ConcurrentHashMap<Int, String>()

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)

            val animeSession = findAnimeSession(meta.anilistId, anime.title) ?: return emptyList()
            val episodeSession = fetchEpisodeSession(animeSession, meta.epNum) ?: return emptyList()

            extractVideos(animeSession, episodeSession)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // Step 1 — search AnimePahe by title and extract session directly
    // =================================================================

    private suspend fun findAnimeSession(anilistId: Int, title: String): String? {
        sessionCache[anilistId]?.let { return it }

        val searchQuery = normalizeSearchQuery(title)
        val words = searchQuery.split(" ").filter { it.isNotBlank() }
        val normalizedTitle = normalizeTitle(title)

        // Try full title first
        var result = searchApiForSession(normalizedTitle, searchQuery)
        if (result != null) {
            sessionCache[anilistId] = result
            return result
        }

        // Fallback: try trailing words (e.g. "Dungeon IV Part 2" -> "IV Part 2")
        for (len in listOf(4, 3)) {
            if (words.size > len) {
                val shortQuery = words.takeLast(len).joinToString(" ")
                result = searchApiForSession(normalizedTitle, shortQuery)
                if (result != null) {
                    sessionCache[anilistId] = result
                    return result
                }
            }
        }
        return null
    }

    private suspend fun searchApiForSession(normalizedTitle: String, query: String): String? {
        val searchUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("m", "search")
            addQueryParameter("q", query)
        }.build()

        return try {
            val result = paheClient.newCall(GET(searchUrl, paheHeaders))
                .awaitSuccess()
                .parseAs<PaheResponseDto<PaheSearchResultDto>>()

            val matched = result.items.firstOrNull { normalizeTitle(it.title) == normalizedTitle }
                ?: result.items.firstOrNull()
            
            matched?.session
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeSearchQuery(raw: String): String = raw
        .replace(Regex("[^a-zA-Z0-9\\s]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTitle(raw: String): String = raw
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

    // =================================================================
    // Step 2 — find the episode session by number
    // =================================================================

    private suspend fun fetchEpisodeSession(animeSession: String, epNum: Int): String? {
        var page = 1
        while (true) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("m", "release")
                addQueryParameter("id", animeSession)
                addQueryParameter("sort", "episode_asc")
                addQueryParameter("page", page.toString())
            }.build()

            val episodesData = paheClient.newCall(GET(url, paheHeaders))
                .awaitSuccess()
                .parseAs<PaheResponseDto<PaheEpisodeDto>>()

            episodesData.items
                .firstOrNull { abs(it.episodeNumber - epNum.toFloat()) < 0.001f }
                ?.let { return it.session }

            if (page >= episodesData.lastPage) break
            page++
        }
        return null
    }

    // =================================================================
    // Step 3 — fetch the play page and extract Kwik videos
    // =================================================================

    private suspend fun extractVideos(animeSession: String, episodeSession: String): List<Video> {
        val response = paheClient.newCall(
            GET("$baseUrl/play/$animeSession/$episodeSession", paheHeaders),
        ).awaitSuccess()

        val document = response.useAsJsoup()

        val downloadLinks = document.select("div#pickDownload > a")
        val links = document.select("div#resolutionMenu > button").withIndex().map { (index, btn) ->
            val kwikLink = btn.attr("data-src")
            val quality = btn.text()
            val paheWinLink = downloadLinks.getOrNull(index)?.attr("href")
            Triple(kwikLink, paheWinLink, quality)
        }

        if (links.isEmpty()) return emptyList()

        val useHLS = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)

        // If HLS is disabled in settings, try MP4 first. Otherwise go straight to HLS.
        val videos = if (!useHLS) {
            val mp4Videos = links.parallelCatchingFlatMap { (_, paheWinLink, quality) ->
                if (paheWinLink.isNullOrBlank()) return@parallelCatchingFlatMap emptyList<Video>()
                KwikExtractor(paheClient, paheHeaders, cfBypassUserAgent)
                    .getStreamVideo(paheWinLink, quality)
                    .let(::listOf)
            }
            AnimePaheHlsServer.processMp4VideoList(paheClient, mp4Videos)
        } else {
            emptyList()
        }

        // Fallback to HLS if MP4 failed or if HLS is preferred
        return videos.ifEmpty {
            val hlsVideos = links.parallelCatchingFlatMap { (kwikLink, _, quality) ->
                KwikExtractor(kwikClient, paheHeaders, cfBypassUserAgent)
                    .getHlsVideo(kwikLink, referer = "$baseUrl/", quality = "$quality (HLS)")
                    .let(::listOf)
            }
            AnimePaheHlsServer.processVideoList(kwikClient, hlsVideos)
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "animepahe_preferred_domain"
        private val PREF_DOMAIN_VALUES = arrayOf("https://animepahe.pw", "https://animepahe.com", "https://animepahe.org")
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.pw"

        private const val PREF_LINK_TYPE_KEY = "animepahe_preferred_link_type"
        private const val PREF_LINK_TYPE_DEFAULT = true

        private const val PREF_CF_UA_KEY = "animepahe_cf_bypass_ua"
    }
}
