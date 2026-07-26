package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * MegaCloud provider — targets HiAnime/AniWave-style sites that use
 * MegaCloud encrypted streams. Uses AniList ID natively.
 *
 * Flow: AniList ID → site search → episode servers → MegaCloudExtractor → m3u8
 */
class MegaCloudProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "MegaCloud"

    companion object {
        // HiAnime API base (adjust domain as needed)
        private const val BASE_URL = "https://hianime.to"
        private const val MEGA_CLOUD_API = "https://megacloud.tv"
    }

    private val extractor by lazy { MegaCloudExtractor(client, headers, MEGA_CLOUD_API) }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class SearchResponse(
        val html: String = "",
    )

    @Serializable
    private data class EpisodeServersResponse(
        val html: String = "",
    )

    @Serializable
    private data class SourceResponse(
        val link: String = "",
    )

    // =================================================================
    // STEP 1: Search by AniList ID → get site anime ID
    // =================================================================

    private suspend fun getAnimeId(anilistId: Int): String? {
        // HiAnime uses a filter/search endpoint that accepts AniList ID
        val url = "$BASE_URL/ajax/search/suggest".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", anilistId.toString())
            .build().toString()

        return try {
            val html = client.newCall(GET(url, apiHeaders))
                .awaitSuccess().bodyString()
            // Parse the anime ID from the search results HTML
            // Format: href="/watch/anime-name-XXXX" where XXXX is the numeric ID
            Regex("""href="/watch/[^"]+-(\d+)"""").find(html)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Get episode list → find episode's data-id
    // =================================================================

    private suspend fun getEpisodeDataId(animeId: String, epNum: Int): String? {
        val url = "$BASE_URL/ajax/v2/episode/list/$animeId"
        return try {
            val html = client.newCall(GET(url, apiHeaders))
                .awaitSuccess().bodyString()
            // Episodes have data-id and data-number attributes
            // Find the one matching our episode number
            val epRegex = Regex("""data-id="(\d+)"[^>]*data-number="(\d+)"""")
            epRegex.findAll(html).firstOrNull {
                it.groupValues[2].toIntOrNull() == epNum
            }?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: Get server list → find MegaCloud embed URL
    // =================================================================

    private suspend fun getMegaCloudEmbedUrl(episodeDataId: String): String? {
        val url = "$BASE_URL/ajax/v2/episode/servers?episodeId=$episodeDataId"
        return try {
            val html = client.newCall(GET(url, apiHeaders))
                .awaitSuccess().bodyString()
            // Look for MegaCloud/VidCloud server link
            // Format: data-id="XXXX" ... data-link-id="YYYY"
            val serverRegex = Regex("""data-id="(\d+)"[^>]*>(?:HD-1|Vidstream|MegaCloud|VidCloud)""")
            val serverId = serverRegex.find(html)?.groupValues?.get(1) ?: return null

            // Get the actual embed URL from the server
            val sourceUrl = "$BASE_URL/ajax/v2/episode/sources?id=$serverId"
            val sourceHtml = client.newCall(GET(sourceUrl, apiHeaders))
                .awaitSuccess().bodyString()
            val sourceData = sourceHtml.parseAs<SourceResponse>()
            sourceData.link.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        if (meta.anilistId == 0) return emptyList()

        return try {
            // Step 1: AniList ID → site anime ID
            val animeId = getAnimeId(meta.anilistId) ?: return emptyList()

            // Step 2: Anime ID + ep number → episode data-id
            val episodeDataId = getEpisodeDataId(animeId, meta.epNum) ?: return emptyList()

            // Step 3: Episode data-id → MegaCloud embed URL
            val embedUrl = getMegaCloudEmbedUrl(episodeDataId) ?: return emptyList()

            // Step 4: Extract videos via MegaCloudExtractor
            val type = if (meta.isDub) "dub" else "sub"
            extractor.getVideosFromUrl(embedUrl, type, name)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
