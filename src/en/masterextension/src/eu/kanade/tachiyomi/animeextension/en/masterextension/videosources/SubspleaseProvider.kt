package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Subsplease video source.
 *
 * Flow:
 *   1. GET /api/?f=search&s=$title → find show slug
 *   2. GET /shows/$slug → extract sid from #show-release-table
 *   3. GET /api/?f=show&sid=$sid → episode list with magnet downloads
 *   4. Filter by episode number → return magnet links as Videos
 *
 * Note: Videos are magnet/torrent links. Requires a debrid service
 * or torrent-capable player to actually stream.
 */
class SubspleaseProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "Subsplease"

    companion object {
        private const val BASE_URL = "https://subsplease.org"
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    // =================================================================
    // STEP 1: Search for the show by title
    // =================================================================
    private suspend fun searchShow(title: String): String? {
        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "search")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("s", title)
            .build().toString()

        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val jObject = json.decodeFromString<JsonObject>(body)

        // Response is a flat object: { "key": { "show": "...", "page": "..." }, ... }
        for (entry in jObject.entries) {
            val item = entry.value.jsonObject
            val showName = item["show"]?.jsonPrimitive?.content ?: continue
            val page = item["page"]?.jsonPrimitive?.content ?: continue
            // Match by title (case-insensitive, trimmed)
            if (showName.equals(title, ignoreCase = true) ||
                showName.contains(title, ignoreCase = true) ||
                title.contains(showName, ignoreCase = true)
            ) {
                return page
            }
        }
        // Fallback: return first result
        return jObject.entries.firstOrNull()?.value?.jsonObject?.get("page")?.jsonPrimitive?.content
    }

    // =================================================================
    // STEP 2: Get the show page and extract sid
    // =================================================================
    private suspend fun getShowId(slug: String): String? {
        val url = "$BASE_URL/shows/$slug"
        val html = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(html)
        val sid = doc.selectFirst("#show-release-table")?.attr("sid")
        return sid?.takeIf { it.isNotBlank() }
    }

    // =================================================================
    // STEP 3: Get episode data and extract magnet links
    // =================================================================
    private suspend fun getEpisodeVideos(sid: String, epNum: Int): List<Video> {
        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "show")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("sid", sid)
            .build().toString()

        val body = client.newCall(GET(url, headers)).awaitSuccess().bodyString()
        val jObject = json.decodeFromString<JsonObject>(body)
        val episodes = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        val videos = mutableListOf<Video>()

        for (entry in episodes) {
            val epObj = entry.value.jsonObject
            val epNumber = epObj["episode"]?.jsonPrimitive?.content ?: continue

            // Match episode number (handle "1", "1.5", "01", etc.)
            val epFloat = epNumber.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: continue
            if (epFloat.toInt() != epNum) continue

            val downloads = epObj["downloads"]?.jsonArray ?: continue
            for (dl in downloads) {
                val dlObj = dl.jsonObject
                val res = dlObj["res"]?.jsonPrimitive?.content ?: continue
                val magnet = dlObj["magnet"]?.jsonPrimitive?.content ?: continue
                videos.add(
                    Video(
                        magnet,
                        "$name ${res}p",
                        magnet,
                    ),
                )
            }
            break // Found our episode, stop
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

        // Step 1: Find the show
        val slug = searchShow(title) ?: return emptyList()

        // Step 2: Get the show ID
        val sid = getShowId(slug) ?: return emptyList()

        // Step 3: Get magnet links for the episode
        return getEpisodeVideos(sid, meta.epNum)
    }
}
