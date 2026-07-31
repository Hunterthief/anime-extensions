package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AnikageProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Anikage"
    override val baseUrl = "https://anikage.cc"

    companion object {
        private const val API_URL = "https://anikage.cc/api/media/anime"
    }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", "https://anikage.cc/")
            .build()
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class SourcesResponse(
        val embeds: List<Embed> = emptyList(),
        val subtitles: List<SubtitleEntry> = emptyList(),
        val intro: SkipTime? = null,
        val outro: SkipTime? = null
    )

    @Serializable
    private data class Embed(
        val url: String = "",
        val type: String = "",
        val server: String = ""
    )

    @Serializable
    private data class SubtitleEntry(
        val file: String = "",
        val label: String = "",
        val kind: String = "",
        val default: Boolean = false,
        val embedUrl: String = ""
    )

    @Serializable
    private data class SkipTime(
        val start: Int = 0,
        val end: Int = 0
    )

    // =================================================================
    // STEP 1: Call sources API (uses AniList ID directly!)
    // =================================================================

    private suspend fun fetchSources(
        anilistId: Int,
        epNum: Int,
        provider: String,
        lang: String
    ): SourcesResponse? {
        val url = "$API_URL/$anilistId/episodes/$epNum/sources".toHttpUrl().newBuilder()
            .addQueryParameter("provider", provider)
            .addQueryParameter("lang", lang)
            .build().toString()

        return try {
            client.newCall(GET(url, apiHeaders))
                .awaitSuccess().bodyString()
                .parseAs<SourcesResponse>()
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Extract m3u8 from bibiemb embed URL
    // =================================================================

    private fun getM3u8FromEmbed(embedUrl: String): Pair<String, Headers>? {
        val cleanUrl = embedUrl.substringBefore("?")

        return when {
            // bibiemb.xyz → workers.dev CDN (access-control-allow-origin: *)
            cleanUrl.contains("bibiemb.xyz") -> {
                val id = cleanUrl.substringAfter("bibiemb.xyz/").trim('/')
                val m3u8 = "https://morning-credit-3bcc.vibevibe.workers.dev/$id/master.m3u8"
                val vidHeaders = headers.newBuilder()
                    .set("Referer", "https://bibiemb.xyz/")
                    .set("Origin", "https://bibiemb.xyz")
                    .build()
                Pair(m3u8, vidHeaders)
            }

            // Skip vivibebe.site (no CORS, doesn't play in ExoPlayer)
            else -> null
        }
    }

    // =================================================================
    // STEP 3: Create Video
    // =================================================================

    private fun createVideo(
        m3u8Url: String,
        vidHeaders: Headers,
        embed: Embed,
        subtitles: List<Track>
    ): Video {
        val typeLabel = when (embed.type) {
            "softsub" -> "Soft Sub"
            "hardsub" -> "Hard Sub"
            else -> embed.type.replaceFirstChar { it.uppercase() }
        }

        return Video(
            url = m3u8Url,
            quality = "$name ${embed.server} $typeLabel Auto",
            videoUrl = m3u8Url,
            headers = vidHeaders,
            subtitleTracks = subtitles,
        )
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        if (meta.anilistId == 0) return emptyList()

        val allVideos = mutableListOf<Video>()

        // Try both sub and dub
        for (lang in listOf("sub", "dub")) {
            val response = fetchSources(meta.anilistId, meta.epNum, "neko", lang) ?: continue

            // Extract subtitle tracks from the response
            val subtitleTracks = response.subtitles
                .filter { it.kind == "captions" && it.embedUrl.isNotBlank() }
                .map { Track(it.embedUrl.substringAfter("sub=").ifBlank { it.file }, it.label) }
                .filter { it.url.startsWith("http") }

            // Process each embed
            for (embed in response.embeds) {
                try {
                    val (m3u8Url, vidHeaders) = getM3u8FromEmbed(embed.url) ?: continue
                    allVideos.add(createVideo(m3u8Url, vidHeaders, embed, subtitleTracks))
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return allVideos.distinctBy { it.videoUrl }
    }
}
