package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
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
 * GogoAnime provider — uses GogoStreamExtractor for Vidstreaming/GogoCDN
 * encrypted streams (AES-CBC via encrypt-ajax.php).
 *
 * Flow: title search → episode page → server iframe URL → GogoStreamExtractor → m3u8
 */
class GogoProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "GogoAnime"

    companion object {
        private const val BASE_URL = "https://anitaku.to"
    }

    private val extractor by lazy { GogoStreamExtractor(client) }

    private val gogoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()
    }

    // =================================================================
    // STEP 1: Search by title → get anime slug
    // =================================================================

    private suspend fun searchAnime(title: String): String? {
        val searchTitle = title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        val url = "$BASE_URL/search.html".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", searchTitle)
            .build().toString()

        return try {
            val doc = client.newCall(GET(url, gogoHeaders))
                .awaitSuccess().asJsoup()
            // First result link: /category/anime-slug
            doc.selectFirst("ul.items li a")?.attr("href")
                ?.substringAfter("/category/")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 2: Get episode page → find Vidstreaming iframe URL
    // =================================================================

    private suspend fun getServerUrl(slug: String, epNum: Int): String? {
        val epSlug = "$slug-episode-$epNum"
        val url = "$BASE_URL/$epSlug"

        return try {
            val doc = client.newCall(GET(url, gogoHeaders))
                .awaitSuccess().asJsoup()
            // Look for the Vidstreaming/GogoCDN iframe
            val iframe = doc.selectFirst("div.anime_muti_link ul li.vidcdn a")
                ?: doc.selectFirst("div.anime_muti_link ul li a[data-video]")
            iframe?.attr("data-video")?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
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
            // Step 1: Search → slug
            val slug = searchAnime(title) ?: return emptyList()

            // Step 2: Slug + ep → server iframe URL
            val serverUrl = getServerUrl(slug, meta.epNum) ?: return emptyList()

            // Step 3: Extract via GogoStreamExtractor (handles AES-CBC decrypt)
            extractor.videosFromUrl(serverUrl)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
