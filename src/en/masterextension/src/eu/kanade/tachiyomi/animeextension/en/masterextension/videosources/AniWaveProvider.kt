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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * AniWave provider — uses AnikotoTheme's AJAX API.
 * Flow: search → episode page → server list → embed URL → getSources → m3u8
 */
class AniWaveProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniWave"

    companion object {
        private const val BASE_URL = "https://animewave.to"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val ajaxHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$BASE_URL/")
            .build()
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class ServerListResponse(
        val result: String = "",
    )

    @Serializable
    private data class EmbedResponse(
        val result: EmbedResult? = null,
    ) {
        @Serializable
        data class EmbedResult(
            val url: String = "",
        )
    }

    @Serializable
    private data class SourceResponse(
        val sources: String = "",
        val tracks: List<TrackDto>? = null,
        val encrypted: Boolean = false,
    ) {
        @Serializable
        data class TrackDto(
            val file: String,
            val kind: String = "",
            val label: String = "",
        )
    }

    // =================================================================
    // STEP 1: Search by title → get anime path
    // =================================================================

    private suspend fun searchAnime(title: String): String? {
        val searchUrl = "$BASE_URL/filter?keyword=${title.replace(" ", "+")}"
        return try {
            val doc = client.newCall(GET(searchUrl, headers)).awaitSuccess().asJsoup()
            doc.selectFirst("div.item a[href]")?.attr("href")
                ?.replace(Regex("-episode-\\d+$"), "")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Get episode page → find episode's server IDs
    // =================================================================

    private suspend fun getEpisodeServerIds(animePath: String, epNum: Int): String? {
        val epUrl = "$BASE_URL$animePath/ep-$epNum"
        return try {
            val doc = client.newCall(GET(epUrl, headers)).awaitSuccess().asJsoup()
            // The episode page has data-ids for servers
            val serverIds = doc.select("div.servers div.type li[data-link-id]")
                .mapNotNull { it.attr("data-link-id").takeIf { id -> id.isNotEmpty() } }
            if (serverIds.isEmpty()) return null
            serverIds.joinToString(",")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: Get server list → parse server IDs and names
    // =================================================================

    private suspend fun getServerList(serverIds: String, epUrl: String): List<Pair<String, String>> {
        val listHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$BASE_URL$epUrl")
            .build()

        return try {
            val response = client.newCall(
                GET("$BASE_URL/ajax/server/list?servers=$serverIds", listHeaders),
            ).awaitSuccess()

            val result = response.parseAs<ServerListResponse>()
            val doc = org.jsoup.Jsoup.parse(result.result)

            doc.select("div.type li[data-link-id]").mapNotNull { li ->
                val id = li.attr("data-link-id")
                val name = li.text().trim()
                if (id.isNotEmpty() && name.isNotEmpty()) id to name else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // STEP 4: Get embed URL for a server
    // =================================================================

    private suspend fun getEmbedUrl(serverId: String, epUrl: String): String? {
        val embedHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$BASE_URL$epUrl")
            .build()

        return try {
            val response = client.newCall(
                GET("$BASE_URL/ajax/server?get=$serverId", embedHeaders),
            ).awaitSuccess()
            response.parseAs<EmbedResponse>().result?.url
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 5: Extract m3u8 from embed page
    // =================================================================

    private suspend fun extractFromEmbed(embedUrl: String, serverName: String): List<Video> {
        val host = try { embedUrl.toHttpUrl().host } catch (_: Exception) { return emptyList() }

        // Fetch the embed page
        val pageBody = try {
            client.newCall(GET(embedUrl, headers.newBuilder().set("Referer", "$BASE_URL/").build()))
                .awaitSuccess().bodyString()
        } catch (_: Exception) {
            return emptyList()
        }

        // Look for data-id attribute (the AnikotoTheme approach)
        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)

        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, serverName)
        }

        // Fallback: look for direct m3u8 in the page
        val m3u8Url = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(pageBody)?.value
        if (m3u8Url != null) {
            return playlistUtils.extractFromHls(
                m3u8Url,
                videoNameGen = { "$serverName - $it" },
                referer = "https://$host/",
            )
        }

        return emptyList()
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        serverName: String,
    ): List<Video> {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .set("Origin", "https://$host")
            .build()

        return try {
            // Try primary endpoint
            val sourceBody = client.newCall(
                GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString()

            val data = sourceBody.parseAs<SourceResponse>()
            val m3u8 = data.sources.takeIf { it.startsWith("http") } ?: return emptyList()

            val subtitles = data.tracks
                ?.filter { it.kind == "captions" }
                ?.map { Track(it.file, it.label) }
                .orEmpty()

            val vidHeaders = headers.newBuilder()
                .set("Referer", "https://$host/")
                .set("Origin", "https://$host")
                .build()

            playlistUtils.extractFromHls(
                m3u8,
                videoNameGen = { "$serverName - $it" },
                subtitleList = subtitles,
                referer = "https://$host/",
                masterHeaders = vidHeaders,
                videoHeaders = vidHeaders,
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        return try {
            // Step 1: Search → anime path
            val animePath = searchAnime(title) ?: return emptyList()

            // Step 2: Get episode page → server IDs
            val epUrl = "$animePath/ep-${meta.epNum}"
            val serverIds = getEpisodeServerIds(animePath, meta.epNum) ?: return emptyList()

            // Step 3: Get server list
            val servers = getServerList(serverIds, epUrl)
            if (servers.isEmpty()) return emptyList()

            // Step 4+5: For each server, get embed URL and extract
            servers.flatMap { (serverId, serverName) ->
                runCatching {
                    val embedUrl = getEmbedUrl(serverId, epUrl) ?: return@flatMap emptyList()
                    extractFromEmbed(embedUrl, serverName)
                }.getOrElse { emptyList() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
