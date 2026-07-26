package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import aniyomi.lib.rapidcloudextractor.RapidCloudExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * RapidCloud provider — targets sites that embed rapid-cloud.co players.
 * Uses RapidCloudExtractor which handles OpenSSL-style AES-CBC decryption
 * with keys fetched from GitHub.
 *
 * This provider searches for RapidCloud embed URLs on supported sites.
 * Currently targets Zoro-style sites that use RapidCloud as a server option.
 */
class RapidCloudProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "RapidCloud"

    companion object {
        // Adjust to a site that uses RapidCloud embeds
        private const val BASE_URL = "https://hianime.to"
    }

    private val extractor by lazy { RapidCloudExtractor(client, headers, preferences) }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // =================================================================
    // STEP 1: Search → anime ID
    // =================================================================

    private suspend fun getAnimeId(title: String): String? {
        val searchTitle = title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        val url = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", searchTitle)
            .build().toString()

        return try {
            val doc = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()
            doc.selectFirst("a.film-poster-ahref, div.film-detail a")
                ?.attr("href")
                ?.substringAfterLast("/")
                ?.substringBefore("?")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Get episode servers → find RapidCloud embed
    // =================================================================

    private suspend fun getRapidCloudUrl(animeSlug: String, epNum: Int): String? {
        // Get episode list to find the episode ID
        val epListUrl = "$BASE_URL/ajax/v2/episode/list/${animeSlug}"
        val epHtml = try {
            client.newCall(GET(epListUrl, apiHeaders)).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        val epRegex = Regex("""data-id="(\d+)"[^>]*data-number="(\d+)"""")
        val episodeId = epRegex.findAll(epHtml).firstOrNull {
            it.groupValues[2].toIntOrNull() == epNum
        }?.groupValues?.get(1) ?: return null

        // Get servers for this episode
        val serversUrl = "$BASE_URL/ajax/v2/episode/servers?episodeId=$episodeId"
        val serversHtml = try {
            client.newCall(GET(serversUrl, apiHeaders)).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        // Find RapidCloud server
        val serverRegex = Regex("""data-id="(\d+)"[^>]*>(?:RapidCloud|Rapid)""")
        val serverId = serverRegex.find(serversHtml)?.groupValues?.get(1) ?: return null

        // Get the embed URL
        val sourceUrl = "$BASE_URL/ajax/v2/episode/sources?id=$serverId"
        return try {
            val sourceHtml = client.newCall(GET(sourceUrl, apiHeaders)).awaitSuccess().bodyString()
            Regex(""""link"\s*:\s*"(https://rapid-cloud\.co/[^"]+)"""")
                .find(sourceHtml)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
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
            val animeSlug = getAnimeId(title) ?: return emptyList()
            val embedUrl = getRapidCloudUrl(animeSlug, meta.epNum) ?: return emptyList()
            val type = "sub"
            extractor.getVideosFromUrl(embedUrl, type, name)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
