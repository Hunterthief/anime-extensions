package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * AnimeKizz video source.
 *
 * Flow:
 *   1. Search by title → anime slug
 *   2. Fetch watch page → anilistId + available servers
 *   3. POST /api/v1/video/resolve → direct video URLs
 *
 * No encryption. No HLS extraction. Plain JSON API.
 */
class AnimeKizzProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AnimeKizz"
    override val baseUrl = "https://animekizz.live"

    companion object {
        private const val BASE_URL = "https://animekizz.live"

        private val DEFAULT_SERVERS = listOf(
            "mimi", "yuki", "sora", "beep", "uwu", "kiwi",
        )
    }

    private val kizzHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Origin", BASE_URL)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
    }

    // =================================================================
    // STEP 1: Search → slug
    // =================================================================

    private suspend fun searchSlug(title: String): String? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE_URL/catalog?q=$encoded&_t=${System.currentTimeMillis()}"

        val doc = client.newCall(GET(url, kizzHeaders))
            .awaitSuccess().useAsJsoup()

        val link = doc.selectFirst("a[href^='/anime/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank() || href == "/anime/") return null

        return href.substringAfter("/anime/").substringBefore("?").trim()
    }

    // =================================================================
    // STEP 2: Watch page → anilistId + servers
    // =================================================================

    private data class WatchPageInfo(
        val anilistId: String,
        val servers: List<String>,
        val subType: String
    )

    private suspend fun fetchWatchPageInfo(slug: String, epNum: Int): WatchPageInfo? {
        val watchUrl = "$BASE_URL/watch/$slug-episode-$epNum"

        val doc = client.newCall(GET(watchUrl, kizzHeaders))
            .awaitSuccess().useAsJsoup()

        // Extract anilist ID
        val anilistLink = doc.select("a[href*='anilist.co/anime/']").attr("href")
        val anilistId = if (anilistLink.isNotBlank()) {
            anilistLink.substringAfterLast("/").substringBefore("?")
        } else {
            "0"
        }

        // Extract available servers
        val servers = doc.select("button[data-slot='button'] span.truncate")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Detect active sub type
        val activeButton = doc.selectFirst("div[data-slot='toggle-group'] button[aria-pressed='true']")
        val subTypeText = activeButton?.selectFirst("span")?.text()?.trim() ?: "Soft Sub"
        val subType = if (subTypeText.equals("Dub", ignoreCase = true)) "dub" else "sub"

        return WatchPageInfo(
            anilistId = anilistId,
            servers = servers.ifEmpty { DEFAULT_SERVERS },
            subType = subType
        )
    }

    // =================================================================
    // STEP 3: Resolve API → videos
    // =================================================================

    private suspend fun resolveVideos(
        episodeId: String,
        serverId: String,
        subTypeLabel: String
    ): List<Video> {
        val jsonBody = """{"episode_id":"$episodeId","server_id":"$serverId"}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val resolveHeaders = kizzHeaders.newBuilder()
            .set("Content-Type", "application/json")
            .build()

        val response = client.newCall(
            POST("$BASE_URL/api/v1/video/resolve", resolveHeaders, requestBody)
        ).awaitSuccess()

        if (!response.isSuccessful) return emptyList()

        val json = Json.parseToJsonElement(response.body.string()).jsonObject
        val sources = json["sources"]?.jsonArray ?: return emptyList()

        val videos = mutableListOf<Video>()

        for (sourceElement in sources) {
            val source = sourceElement.jsonObject
            val videoPath = source["url"]?.jsonPrimitive?.content ?: continue
            val quality = source["quality"]?.jsonPrimitive?.content ?: "Auto"
            val serverName = source["server"]?.jsonPrimitive?.content ?: serverId

            // Subtitles
            val subtitleTracks = mutableListOf<Track>()
            source["subtitles"]?.jsonArray?.forEach { subElement ->
                val sub = subElement.jsonObject
                val subPath = sub["url"]?.jsonPrimitive?.content ?: ""
                val subLang = sub["label"]?.jsonPrimitive?.content ?: "Unknown"
                if (subPath.isNotBlank()) {
                    val fullSubUrl = if (subPath.startsWith("http")) subPath else "$BASE_URL$subPath"
                    subtitleTracks.add(Track(fullSubUrl, subLang))
                }
            }

            // Headers from response
            val sourceHeaders = kizzHeaders.newBuilder()
            source["headers"]?.jsonObject?.let { headersObj ->
                headersObj["Referer"]?.jsonPrimitive?.content?.let { ref ->
                    sourceHeaders.set("Referer", ref)
                }
            }

            val finalUrl = if (videoPath.startsWith("http")) videoPath else "$BASE_URL$videoPath"
            val displayName = "${serverName.split(":")[0].replaceFirstChar { it.uppercase() }} - $quality ($subTypeLabel)"

            videos.add(
                Video(
                    url = finalUrl,
                    quality = displayName,
                    videoUrl = finalUrl,
                    headers = sourceHeaders.build(),
                    subtitleTracks = subtitleTracks
                )
            )
        }

        return videos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        // Step 1: Find the anime slug
        val slug = searchSlug(title) ?: return emptyList()

        // Step 2: Get watch page info (anilistId, servers, subType)
        val pageInfo = fetchWatchPageInfo(slug, meta.epNum) ?: return emptyList()

        // Build episode ID (same format as the working extension)
        val episodeId = if (pageInfo.anilistId != "0") {
            "$slug-${pageInfo.anilistId}:${meta.epNum}"
        } else {
            "$slug:${meta.epNum}"
        }

        val subTypeLabel = if (pageInfo.subType == "dub") "Dub" else "Soft Sub"

        // Step 3: Try each server
        val allVideos = mutableListOf<Video>()

        for (server in pageInfo.servers) {
            val serverId = "$server:${pageInfo.subType}"
            try {
                val videos = resolveVideos(episodeId, serverId, subTypeLabel)
                allVideos.addAll(videos)
            } catch (_: Exception) {
                continue
            }
        }

        // Also try the opposite sub type if we got nothing
        if (allVideos.isEmpty() && pageInfo.subType == "sub") {
            for (server in pageInfo.servers) {
                val serverId = "$server:dub"
                try {
                    val videos = resolveVideos(episodeId, serverId, "Dub")
                    allVideos.addAll(videos)
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return allVideos
    }
}
