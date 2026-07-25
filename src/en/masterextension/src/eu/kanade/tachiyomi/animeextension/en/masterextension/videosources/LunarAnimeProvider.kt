package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * LunarAnime video source.
 *
 * Two API paths:
 *   A) /api/animes/vermillion/sources?id=$malId&host=$host&epNum=$ep&type=sub
 *      → returns sources with URLs (may be base64url-encoded or direct)
 *   B) /api/3rdprovider?anilist=$anilistId&episode=$ep
 *      → returns FlixCloud player URLs (m3u8 is encrypted — used as fallback)
 *
 * Supports HLS (.m3u8) and DASH (.mpd) output.
 */
class LunarAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "LunarAnime"

    companion object {
        private const val API_URL = "https://api.lunaranime.ru"
        private const val SITE_URL = "https://lunaranime.ru"

        // Hosts to try via the vermillion API (order matters)
        private val VERMILLION_HOSTS = listOf("animeonsen", "kiwi", "flixcloud")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // LOCAL DTOs
    // =================================================================

    @Serializable
    private data class VermillionResponse(
        val success: Boolean = false,
        val data: VermillionData? = null
    )

    @Serializable
    private data class VermillionData(
        val sources: List<VermillionSource> = emptyList(),
        val subtitles: List<VermillionSubtitle> = emptyList(),
        val headers: Map<String, String>? = null
    )

    @Serializable
    private data class VermillionSource(
        val url: String = "",
        val quality: String = "auto",
        val type: String = "",
        val isM3U8: Boolean = false
    )

    @Serializable
    private data class VermillionSubtitle(
        val url: String = "",
        val lang: String = ""
    )

    @Serializable
    private data class ThirdPartyResponse(
        val success: Boolean = false,
        val data: List<ThirdPartySource> = emptyList()
    )

    @Serializable
    private data class ThirdPartySource(
        val player_url: String = "",
        val server: String = "",
        val audio: String = ""
    )

    // =================================================================
    // HEADERS
    // =================================================================

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", "$SITE_URL/")
            .set("Origin", SITE_URL)
            .build()
    }

    // =================================================================
    // PATH A: Vermillion API (preferred — returns direct/encoded URLs)
    // =================================================================

    private suspend fun fetchFromVermillion(
        malId: Int,
        epNum: Int,
        host: String
    ): List<Video> {
        val url = "$API_URL/api/animes/vermillion/sources".toHttpUrl().newBuilder()
            .addQueryParameter("id", malId.toString())
            .addQueryParameter("host", host)
            .addQueryParameter("epNum", epNum.toString())
            .addQueryParameter("type", "sub")
            .build().toString()

        val body = client.newCall(GET(url, apiHeaders))
            .awaitSuccess().bodyString()

        val response = body.parseAs<VermillionResponse>()
        if (!response.success || response.data == null) return emptyList()

        val data = response.data
        val sourceHeaders = data.headers

        val subtitles = data.subtitles
            .filter { it.url.isNotBlank() }
            .map { Track(it.url, it.lang) }

        return data.sources.parallelCatchingFlatMap { source ->
            val resolvedUrl = resolveSourceUrl(source.url)
            if (resolvedUrl.isBlank()) return@parallelCatchingFlatMap emptyList<Video>()

            val refererHost = sourceHeaders?.get("Origin")
                ?: resolvedUrl.toHttpUrl().let { "https://${it.host}" }

            when {
                // DASH manifest — pass directly (ExoPlayer handles it)
                resolvedUrl.contains(".mpd") -> {
                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "$SITE_URL/")
                        .set("Origin", SITE_URL)
                        .build()

                    listOf(Video(
                        resolvedUrl,
                        "$name $host DASH ${source.quality}",
                        resolvedUrl,
                        headers = vidHeaders,
                        subtitleTracks = subtitles,
                    ))
                }

                // HLS playlist
                resolvedUrl.contains(".m3u8") || source.isM3U8 -> {
                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "$refererHost/")
                        .set("Origin", refererHost)
                        .build()

                    playlistUtils.extractFromHls(
                        resolvedUrl,
                        videoNameGen = { quality -> "$name $host $quality" },
                        subtitleList = subtitles,
                        referer = "$refererHost/",
                        masterHeaders = vidHeaders,
                        videoHeaders = vidHeaders,
                    )
                }

                // Direct video file
                else -> {
                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "$refererHost/")
                        .build()

                    listOf(Video(
                        resolvedUrl,
                        "$name $host ${source.quality}",
                        resolvedUrl,
                        headers = vidHeaders,
                        subtitleTracks = subtitles,
                    ))
                }
            }
        }
    }

    // =================================================================
    // PATH B: 3rdprovider API (FlixCloud fallback)
    // =================================================================

    private suspend fun fetchFromThirdParty(
        anilistId: Int,
        epNum: Int
    ): List<Video> {
        val url = "$API_URL/api/3rdprovider".toHttpUrl().newBuilder()
            .addQueryParameter("anilist", anilistId.toString())
            .addQueryParameter("episode", epNum.toString())
            .addQueryParameter("autoplay", "true")
            .build().toString()

        val body = client.newCall(GET(url, apiHeaders))
            .awaitSuccess().bodyString()

        val response = body.parseAs<ThirdPartyResponse>()
        if (!response.success) return emptyList()

        return response.data.parallelCatchingFlatMap { source ->
            if (source.player_url.isBlank()) return@parallelCatchingFlatMap emptyList<Video>()
            extractFromFlixCloud(source)
        }
    }

    private suspend fun extractFromFlixCloud(source: ThirdPartySource): List<Video> {
        val playerUrl = source.player_url
        val host = playerUrl.toHttpUrl().host
        val audioLabel = when (source.audio) {
            "dual" -> "Sub/Dub"
            "dub" -> "Dub"
            else -> "Sub"
        }

        val playerHeaders = headers.newBuilder()
            .set("Referer", "$SITE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageBody = client.newCall(GET(playerUrl, playerHeaders))
            .awaitSuccess().bodyString()

        // Try to find m3u8 URL in the page (multiple patterns)
        val m3u8Patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*[:=]\s*["'](https?://[^"']+)["']"""),
            Regex("""source\s*[:=]\s*["'](https?://[^"']+)["']"""),
        )

        for (pattern in m3u8Patterns) {
            val match = pattern.find(pageBody)
            if (match != null) {
                val m3u8Url = match.groupValues.last()
                if (m3u8Url.startsWith("http")) {
                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "https://$host/")
                        .set("Origin", "https://$host")
                        .build()

                    val videos = playlistUtils.extractFromHls(
                        m3u8Url,
                        videoNameGen = { quality -> "$name FlixCloud $audioLabel $quality" },
                        referer = "https://$host/",
                        masterHeaders = vidHeaders,
                        videoHeaders = vidHeaders,
                    )
                    if (videos.isNotEmpty()) return videos
                }
            }
        }

        // Try data-id → sources API pattern
        val dataId = Regex("""data-id\s*=\s*"([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            for (endpoint in listOf("ajax/embed-6/getSources", "ajax/getSources", "stream/getSources")) {
                try {
                    val ajaxUrl = "https://$host/$endpoint?id=$dataId"
                    val ajaxHeaders = headers.newBuilder()
                        .set("Accept", "*/*")
                        .set("X-Requested-With", "XMLHttpRequest")
                        .set("Referer", playerUrl)
                        .set("Origin", "https://$host")
                        .build()

                    val ajaxBody = client.newCall(GET(ajaxUrl, ajaxHeaders))
                        .awaitSuccess().bodyString()

                    // Try to find m3u8 in the response
                    val fileMatch = Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""").find(ajaxBody)
                        ?: Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(ajaxBody)

                    if (fileMatch != null) {
                        val m3u8 = fileMatch.groupValues.last()
                        val vidHeaders = headers.newBuilder()
                            .set("Referer", "https://$host/")
                            .set("Origin", "https://$host")
                            .build()

                        return playlistUtils.extractFromHls(
                            m3u8,
                            videoNameGen = { quality -> "$name FlixCloud $audioLabel $quality" },
                            referer = "https://$host/",
                            masterHeaders = vidHeaders,
                            videoHeaders = vidHeaders,
                        )
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return emptyList()
    }

    // =================================================================
    // URL RESOLUTION — handles encoded URLs
    // =================================================================

    private fun resolveSourceUrl(rawUrl: String): String {
        // Already a direct URL
        if (rawUrl.startsWith("http")) return rawUrl

        // Try base64url decode (kiwi host uses this)
        try {
            val decoded = String(Base64.decode(rawUrl, Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) { }

        // Try standard base64 decode
        try {
            val decoded = String(Base64.decode(rawUrl, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) { }

        // Try base64 with padding added
        try {
            val padded = rawUrl + "=".repeat((4 - rawUrl.length % 4) % 4)
            val decoded = String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) { }

        return ""
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        if (meta.anilistId == 0) return emptyList()

        val allVideos = mutableListOf<Video>()

        // Path A: Try vermillion API with each host
        if (meta.malId != 0) {
            for (host in VERMILLION_HOSTS) {
                try {
                    val videos = fetchFromVermillion(meta.malId, meta.epNum, host)
                    if (videos.isNotEmpty()) {
                        allVideos.addAll(videos)
                        break // Got videos from one host, no need to try others
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        }

        // Path B: Fallback to 3rdprovider (FlixCloud)
        if (allVideos.isEmpty()) {
            try {
                allVideos.addAll(fetchFromThirdParty(meta.anilistId, meta.epNum))
            } catch (_: Exception) { }
        }

        return allVideos
    }
}
