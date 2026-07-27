package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
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
 * Subsplease video source — magnet-link based.
 *
 * Subsplease provides torrent magnet links directly via a JSON API.
 * Aniyomi's built-in libtorrent engine handles magnet: URIs natively,
 * so no extraction, decryption, or m3u8 parsing is needed.
 *
 * Flow:
 *   1. Search API by title → get show slug
 *   2. Fetch show page HTML → extract sid
 *   3. Episode API with sid + epNum → magnet links
 *   4. Return magnets as Video objects → Aniyomi streams via P2P
 */
class SubspleaseProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Subsplease"

    companion object {
        private const val BASE_URL = "https://subsplease.org"
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val siteHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()
    }

    // =================================================================
    // STEP 1: Search by title → get show page slug
    // =================================================================
    private suspend fun searchShow(title: String): String? {
        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "search")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("s", title)
            .build().toString()

        val body = client.newCall(GET(url, siteHeaders))
            .awaitSuccess().bodyString()

        val jObject = json.decodeFromString<JsonObject>(body)
        // Response is a flat object: { "0": {show, page, image_url}, "1": {...}, ... }
        for ((_, value) in jObject) {
            val entry = value.jsonObject
            val show = entry["show"]?.jsonPrimitive?.content ?: continue
            val page = entry["page"]?.jsonPrimitive?.content ?: continue
            // Match by title (case-insensitive, trim)
            if (show.equals(title, ignoreCase = true) ||
                title.contains(show, ignoreCase = true) ||
                show.contains(title, ignoreCase = true)
            ) {
                return page
            }
        }
        // Fallback: return first result
        val first = jObject.values.firstOrNull()?.jsonObject
        return first?.get("page")?.jsonPrimitive?.content
    }

    // =================================================================
    // STEP 2: Fetch show page → extract sid
    // =================================================================
    private suspend fun getShowId(slug: String): String? {
        val url = "$BASE_URL/shows/$slug"
        val html = client.newCall(GET(url, siteHeaders))
            .awaitSuccess().bodyString()

        val doc = Jsoup.parse(html)
        val sid = doc.selectFirst("#show-release-table")?.attr("sid")
        return sid?.takeIf { it.isNotBlank() }
    }

    // =================================================================
    // STEP 3: Episode API → magnet links
    // =================================================================
    private suspend fun getMagnets(sid: String, epNum: Int): List<Video> {
        val url = "$BASE_URL/api/".toHttpUrl().newBuilder()
            .addQueryParameter("f", "show")
            .addQueryParameter("tz", "Europe/Berlin")
            .addQueryParameter("sid", sid)
            .addQueryParameter("num", epNum.toString())
            .build().toString()

        val body = client.newCall(GET(url, siteHeaders))
            .awaitSuccess().bodyString()

        val jObject = json.decodeFromString<JsonObject>(body)
        val episodes = jObject["episode"]?.jsonObject?.entries ?: return emptyList()

        val videos = mutableListOf<Video>()
        for ((_, value) in episodes) {
            val epObj = value.jsonObject
            val epStr = epObj["episode"]?.jsonPrimitive?.content ?: continue
            // Match episode number (handle "1", "1.5", etc.)
            val epFloat = epStr.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: continue
            if (epFloat.toInt() != epNum) continue

            val downloads = epObj["downloads"]?.jsonArray ?: continue
            for (dl in downloads) {
                val dlObj = dl.jsonObject
                val res = dlObj["res"]?.jsonPrimitive?.content ?: continue
                val magnet = dlObj["magnet"]?.jsonPrimitive?.content ?: continue
                if (!magnet.startsWith("magnet:")) continue

                videos.add(
                    Video(
                        magnet,
                        "$name ${res}p",
                        magnet
                    )
                )
            }
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

        // Step 3: Get magnets for this episode
        return getMagnets(sid, meta.epNum)
    }
}
