package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anikoto.AnikotoExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anikoto.AnikotoResultResponse
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anikoto.AnikotoVrf
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

class AnikotoProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "Anikoto"
    override val baseUrl = "https://anikototv.to"

    // =================================================================
    // DEDICATED CLIENT — clean, no CloudflareInterceptor
    // Anikoto is behind Cloudflare CDN but does not challenge requests.
    // =================================================================

    private val anikotoClient: OkHttpClient by lazy {
        client.newBuilder()
            .apply { networkInterceptors().clear() }
            .build()
    }

    private val anikotoHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
    }

    private val playlistClient by lazy {
        anikotoClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(playlistClient, anikotoHeaders) }

    private val m3u8Client by lazy {
        anikotoClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val m3u8ServerManager by lazy { M3u8ServerManager(m3u8Client) }

    private val extractor by lazy {
        AnikotoExtractor(anikotoClient, anikotoHeaders, baseUrl, playlistUtils, m3u8ServerManager)
    }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // =================================================================
    // Caches
    // =================================================================

    private data class AnimeInfo(val path: String, val id: String)

    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    private data class EpisodeInfo(
        val ids: String,
        val epUrl: String,
        val malId: String,
        val slug: String,
        val ts: String,
    )

    // =================================================================
    // VideoProvider contract
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)

            val info = findAnime(meta.anilistId, anime.title) ?: return emptyList()
            val epInfo = findEpisode(info, meta.epNum) ?: return emptyList()

            ensureM3u8ServerRunning()
            extractVideos(epInfo)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // Step 1 — search and resolve anime
    // =================================================================

    private suspend fun findAnime(anilistId: Int, title: String): AnimeInfo? {
        animeCache[anilistId]?.let { return it }

        val vrf = AnikotoVrf.encrypt(title)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")
            addQueryParameter("keyword", title)
            addQueryParameter("page", "1")
            addQueryParameter("vrf", vrf)
        }.build()

        val document = anikotoClient.newCall(GET(url, anikotoHeaders, cacheControl))
            .awaitSuccess()
            .useAsJsoup()

        val items = document.select("div.ani.items > div.item")
            .ifEmpty { document.select("div.item") }
            .ifEmpty { document.select("a[href*=/watch/]") }

        if (items.isEmpty()) return null

        val titleLower = title.lowercase().trim()

        val bestElement = items.firstOrNull { item ->
            val name = (item.selectFirst("a.name") ?: item.selectFirst("a"))
                ?.text()?.lowercase()?.trim() ?: ""
            name == titleLower
        } ?: items.minByOrNull { item ->
            val name = (item.selectFirst("a.name") ?: item.selectFirst("a"))
                ?.text()?.lowercase()?.trim() ?: ""
            when {
                name.startsWith(titleLower) -> name.length
                titleLower.startsWith(name) -> name.length + 1000
                name.contains(titleLower) -> name.length + 2000
                else -> Int.MAX_VALUE
            }
        } ?: items.firstOrNull()

        val href = (bestElement?.selectFirst("a.name") ?: bestElement?.selectFirst("a[href*=/watch/]"))
            ?.attr("href") ?: return null

        val animePath = EP_URL_SUFFIX_REGEX.replace(href.substringBefore("?"), "")
            .takeIf { it.startsWith("/watch/") } ?: return null

        val animeId = fetchAnimeId(animePath) ?: return null

        val info = AnimeInfo(animePath, animeId)
        animeCache[anilistId] = info
        return info
    }

    private suspend fun fetchAnimeId(animePath: String): String? {
        return try {
            val document = anikotoClient.newCall(GET(baseUrl + animePath, anikotoHeaders))
                .awaitSuccess()
                .useAsJsoup()

            document.selectFirst("[data-id]")?.attr("data-id")
                ?: document.selectFirst("[data-tip]")?.attr("data-tip")
                ?: document.selectFirst("#watch-page[data-id]")?.attr("data-id")
                ?: document.selectFirst(".watch-page[data-id]")?.attr("data-id")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // Step 2 — find episode by number
    // =================================================================

    private suspend fun findEpisode(info: AnimeInfo, epNum: Int): EpisodeInfo? {
        val listHeaders = anikotoHeaders.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + info.path)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val vrf = AnikotoVrf.encrypt(info.id)
        val response = anikotoClient.newCall(
            GET("$baseUrl/ajax/episode/list/${info.id}?vrf=$vrf", listHeaders),
        ).awaitSuccess()

        val document = response.parseAs<AnikotoResultResponse>().toDocument()

        val allEpisodes = document.select("div.episodes ul > li > a")
            .ifEmpty { document.select("ul > li > a[data-num]") }
            .ifEmpty { document.select("a[data-ids]") }

        if (allEpisodes.isEmpty()) return null

        val episodeElement = allEpisodes.firstOrNull { a ->
            val num = a.attr("data-num")
            num.toFloatOrNull()?.toInt() == epNum
        } ?: allEpisodes.getOrNull(epNum - 1)

        if (episodeElement == null) return null

        val ids = episodeElement.attr("data-ids")
        if (ids.isEmpty()) return null

        val epNumStr = episodeElement.attr("data-num").ifEmpty { epNum.toString() }
        val malId = episodeElement.attr("data-mal")
        val slug = episodeElement.attr("data-slug")
        val ts = episodeElement.attr("data-timestamp")

        val epUrl = "${EP_URL_SUFFIX_REGEX.replace(info.path, "")}/ep-$epNumStr"

        return EpisodeInfo(ids, epUrl, malId, slug, ts)
    }
    // =================================================================
    // Step 3 — fetch server list and extract videos
    // =================================================================

    private suspend fun extractVideos(epInfo: EpisodeInfo): List<Video> {
        val listHeaders = anikotoHeaders.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl${epInfo.epUrl}")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val response = anikotoClient.newCall(
            GET("$baseUrl/ajax/server/list?servers=${epInfo.ids}", listHeaders),
        ).awaitSuccess()

        val document = response.parseAs<AnikotoResultResponse>().toDocument()

        return extractor.extractVideos(
            document = document,
            episodeIds = epInfo.ids,
            epUrl = epInfo.epUrl,
            malId = epInfo.malId,
            slug = epInfo.slug,
            ts = epInfo.ts,
        )
    }

    // =================================================================
    // M3U8 server
    // =================================================================

    private suspend fun ensureM3u8ServerRunning() {
        if (m3u8ServerManager.isRunning()) return
        try {
            m3u8ServerManager.startServer()
            val deadline = System.currentTimeMillis() + 2000L
            while (!m3u8ServerManager.isRunning() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(50L)
            }
        } catch (_: Exception) { }
    }

    companion object {
        private val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")
    }
}
