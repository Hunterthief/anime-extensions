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
    override val baseUrl = "https://animepahe.pw"

    // =================================================================
    // DEDICATED CLIENTS — mirrors the OG extension's chain exactly
    //
    // cleanClient  → network.client equivalent (no custom interceptors)
    // paheClient   → cleanClient + DdosGuardInterceptor  (OG's "client")
    // kwikClient   → cleanClient, no DdosGuardInterceptor (OG's "extractorClient")
    //
    // DdosGuardInterceptor is constructed with cleanClient so its
    // internal getNewCookie() calls never touch CloudflareInterceptor.
    // =================================================================

    private val cleanClient: OkHttpClient by lazy {
        client.newBuilder()
            .apply { networkInterceptors().clear() }
            .build()
    }

    private val paheClient: OkHttpClient by lazy {
        cleanClient.newBuilder()
            .addInterceptor(DdosGuardInterceptor(cleanClient) { ANIMEPAHE_UA })
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

    // =================================================================
    // Caches
    // =================================================================

    private val animeIdCache = ConcurrentHashMap<Int, Int>()
    private val sessionCache = ConcurrentHashMap<Int, String>()

    // =================================================================
    // VideoProvider contract
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)

            val animeId = findAnimeId(meta.anilistId, anime.title) ?: return emptyList()
            val animeSession = fetchSession(animeId) ?: return emptyList()
            val episodeSession = fetchEpisodeSession(animeSession, meta.epNum) ?: return emptyList()

            extractVideos(animeSession, episodeSession)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // Step 1 — search AnimePahe by title
    // =================================================================

    private suspend fun findAnimeId(anilistId: Int, title: String): Int? {
        animeIdCache[anilistId]?.let { return it }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("m", "search")
            addQueryParameter("q", title)
        }.build()

        val result = paheClient.newCall(GET(url, paheHeaders))
            .awaitSuccess()
            .parseAs<PaheResponseDto<PaheSearchResultDto>>()

        val items = result.items
        if (items.isEmpty()) return null

        val titleLower = title.lowercase().trim()

        val best = items.firstOrNull { it.title.lowercase().trim() == titleLower }
            ?: items.minByOrNull { item ->
                val n = item.title.lowercase().trim()
                when {
                    n.startsWith(titleLower) -> n.length
                    titleLower.startsWith(n) -> n.length + 1000
                    n.contains(titleLower) -> n.length + 2000
                    else -> Int.MAX_VALUE
                }
            }
            ?: items.firstOrNull()

        val animeId = best?.id ?: return null
        animeIdCache[anilistId] = animeId
        return animeId
    }

    // =================================================================
    // Step 2 — resolve the anime session (AnimePahe uses ephemeral sessions)
    // =================================================================

    private suspend fun fetchSession(animeId: Int): String? {
        sessionCache[animeId]?.let { return it }

        return try {
            val session = paheClient.newCall(GET("$baseUrl/a/$animeId", paheHeaders))
                .awaitSuccess()
                .use { it.request.url.pathSegments.last() }

            sessionCache[animeId] = session
            session
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // Step 3 — find the episode session by number
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
    // Step 4 — fetch the play page and extract Kwik videos
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

        val hlsVideos = links.parallelCatchingFlatMap { (kwikLink, _, quality) ->
            KwikExtractor(kwikClient, paheHeaders, ANIMEPAHE_UA)
                .getHlsVideo(kwikLink, referer = "$baseUrl/", quality = "$quality (HLS)")
                .let(::listOf)
        }

        val processed = AnimePaheHlsServer.processVideoList(kwikClient, hlsVideos)
        if (processed.isNotEmpty()) return processed

        val mp4Videos = links.parallelCatchingFlatMap { (_, paheWinLink, quality) ->
            if (paheWinLink.isNullOrBlank()) return@parallelCatchingFlatMap emptyList<Video>()
            KwikExtractor(paheClient, paheHeaders, ANIMEPAHE_UA)
                .getStreamVideo(paheWinLink, quality)
                .let(::listOf)
        }

        return AnimePaheHlsServer.processMp4VideoList(paheClient, mp4Videos)
    }
}
