package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

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
import org.jsoup.Jsoup

/**
 * LunarAnime video source.
 *
 * Uses the LunarAnime public API which accepts AniList IDs directly.
 * No title search needed — the EpisodeMeta already has the AniList ID.
 *
 * Flow:
 *   1. GET api.lunaranime.ru/api/3rdprovider?anilist=$id&episode=$ep
 *   2. Parse JSON → list of player_url entries
 *   3. Fetch each embed page → extract m3u8
 *   4. PlaylistUtils → quality variants
 */
class LunarAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "LunarAnime"

    companion object {
        private const val API_URL = "https://api.lunaranime.ru"
        private const val SITE_URL = "https://lunaranime.ru"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // LOCAL DTOs
    // =================================================================

    @Serializable
    private data class LunarResponse(
        val success: Boolean = false,
        val data: List<LunarSource> = emptyList()
    )

    @Serializable
    private data class LunarSource(
        val access_id: String = "",
        val player_url: String = "",
        val server: String = "",
        val audio: String = "",
        val episode: Int = 0
    )

    @Serializable
    private data class EmbedSourceResponse(
        val sources: List<EmbedSource>? = null,
        val tracks: List<EmbedTrack>? = null
    )

    @Serializable
    private data class EmbedSource(
        val file: String = "",
        val type: String = ""
    )

    @Serializable
    private data class EmbedTrack(
        val file: String = "",
        val kind: String = "",
        val label: String = ""
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
    // STEP 1: Call the LunarAnime API
    // =================================================================

    private suspend fun fetchSources(anilistId: Int, epNum: Int): List<LunarSource> {
        val url = "$API_URL/api/3rdprovider".toHttpUrl().newBuilder()
            .addQueryParameter("anilist", anilistId.toString())
            .addQueryParameter("episode", epNum.toString())
            .addQueryParameter("autoplay", "true")
            .build().toString()

        val body = client.newCall(GET(url, apiHeaders))
            .awaitSuccess().bodyString()

        val response = body.parseAs<LunarResponse>()
        if (!response.success) return emptyList()

        return response.data.filter { it.player_url.isNotBlank() }
    }

    // =================================================================
    // STEP 2: Extract m3u8 from the embed player page
    // =================================================================

    private suspend fun extractFromPlayer(source: LunarSource): List<Video> {
        val playerUrl = source.player_url
        val host = playerUrl.toHttpUrl().host
        val audioLabel = when (source.audio) {
            "dual" -> "Sub/Dub"
            "dub" -> "Dub"
            else -> "Sub"
        }
        val serverLabel = source.server.ifBlank { "Default" }

        val playerHeaders = headers.newBuilder()
            .set("Referer", "$SITE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageBody = client.newCall(GET(playerUrl, playerHeaders))
            .awaitSuccess().bodyString()

        // Strategy 1: data-id → AJAX sources API (MegaCloud / FlixCloud pattern)
        val dataId = Regex("""data-id\s*=\s*"([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            val videos = fetchFromSourcesApi(dataId, host, playerUrl, serverLabel, audioLabel)
            if (videos.isNotEmpty()) return videos
        }

        // Strategy 2: direct m3u8 URL in the page
        val m3u8Match = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(pageBody)
        if (m3u8Match != null) {
            return extractHls(m3u8Match.value, host, serverLabel, audioLabel)
        }

        // Strategy 3: <source> tag with m3u8
        val doc = Jsoup.parse(pageBody)
        val sourceTag = doc.selectFirst("source[src*='.m3u8']")?.attr("src")
        if (!sourceTag.isNullOrBlank()) {
            return extractHls(sourceTag, host, serverLabel, audioLabel)
        }

        // Strategy 4: JS variable assignment containing m3u8
        val jsVar = Regex(
            """(?:file|source|url|src|playlist)\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']"""
        ).find(pageBody)
        if (jsVar != null) {
            return extractHls(jsVar.groupValues[1], host, serverLabel, audioLabel)
        }

        // Strategy 5: iframe → follow it one level
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val resolved = resolveUrl(iframeSrc, playerUrl)
            val iframeHost = resolved.toHttpUrl().host
            val iframeBody = client.newCall(GET(resolved, playerHeaders))
                .awaitSuccess().bodyString()

            val iframeM3u8 = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(iframeBody)
            if (iframeM3u8 != null) {
                return extractHls(iframeM3u8.value, iframeHost, serverLabel, audioLabel)
            }

            val iframeDataId = Regex("""data-id\s*=\s*"([^"]+)"""").find(iframeBody)?.groupValues?.get(1)
            if (iframeDataId != null) {
                val videos = fetchFromSourcesApi(iframeDataId, iframeHost, resolved, serverLabel, audioLabel)
                if (videos.isNotEmpty()) return videos
            }
        }

        // Strategy 6: /ajax/embed patterns (common across HiAnime clones)
        for (endpoint in listOf("ajax/embed-6/getSources", "ajax/embed-4/getSources", "ajax/getSources")) {
            try {
                val ajaxUrl = "https://$host/$endpoint?id=${dataId ?: source.access_id}"
                val ajaxHeaders = headers.newBuilder()
                    .set("Accept", "*/*")
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Referer", playerUrl)
                    .set("Origin", "https://$host")
                    .build()

                val ajaxBody = client.newCall(GET(ajaxUrl, ajaxHeaders))
                    .awaitSuccess().bodyString()

                val sourceData = ajaxBody.parseAs<EmbedSourceResponse>()
                val m3u8 = sourceData.sources?.firstOrNull { it.file.isNotBlank() }?.file
                if (m3u8 != null && m3u8.startsWith("http")) {
                    val subtitles = sourceData.tracks
                        ?.filter { it.kind == "captions" }
                        ?.map { Track(it.file, it.label) }
                        .orEmpty()

                    return extractHlsWithSubs(m3u8, host, subtitles, serverLabel, audioLabel)
                }
            } catch (_: Exception) {
                continue
            }
        }

        return emptyList()
    }

    // =================================================================
    // Sources API (data-id pattern)
    // =================================================================

    private suspend fun fetchFromSourcesApi(
        dataId: String,
        host: String,
        refererUrl: String,
        serverLabel: String,
        audioLabel: String
    ): List<Video> {
        val ajaxHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", refererUrl)
            .set("Origin", "https://$host")
            .build()

        for (endpoint in listOf(
            "ajax/embed-6/getSources",
            "ajax/embed-4/getSources",
            "ajax/getSources",
            "stream/getSources"
        )) {
            try {
                val url = "https://$host/$endpoint?id=$dataId"
                val body = client.newCall(GET(url, ajaxHeaders))
                    .awaitSuccess().bodyString()

                val sourceData = body.parseAs<EmbedSourceResponse>()
                val m3u8 = sourceData.sources?.firstOrNull { it.file.isNotBlank() }?.file
                if (m3u8 != null && m3u8.startsWith("http")) {
                    val subtitles = sourceData.tracks
                        ?.filter { it.kind == "captions" }
                        ?.map { Track(it.file, it.label) }
                        .orEmpty()

                    return extractHlsWithSubs(m3u8, host, subtitles, serverLabel, audioLabel)
                }
            } catch (_: Exception) {
                continue
            }
        }

        return emptyList()
    }

    // =================================================================
    // HLS EXTRACTION
    // =================================================================

    private suspend fun extractHls(
        m3u8Url: String,
        host: String,
        serverLabel: String,
        audioLabel: String
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "$name $serverLabel $audioLabel $quality" },
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    private suspend fun extractHlsWithSubs(
        m3u8Url: String,
        host: String,
        subtitles: List<Track>,
        serverLabel: String,
        audioLabel: String
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "$name $serverLabel $audioLabel $quality" },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // UTILS
    // =================================================================

    private fun resolveUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.toHttpUrl().resolve(url)?.toString() ?: url
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)

        // LunarAnime uses AniList IDs directly — no title search needed
        if (meta.anilistId == 0) return emptyList()

        val sources = fetchSources(meta.anilistId, meta.epNum)
        if (sources.isEmpty()) return emptyList()

        return sources.parallelCatchingFlatMap { source ->
            extractFromPlayer(source)
        }
    }
}
