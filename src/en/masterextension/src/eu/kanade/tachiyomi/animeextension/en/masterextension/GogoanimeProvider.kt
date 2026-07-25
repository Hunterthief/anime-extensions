package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Gogoanime provider — searches by title via the Gogoanime search page,
 * constructs the episode URL from the anime slug, then extracts video
 * sources from the episode page's embedded server links.
 *
 * ID Resolution: Title-based search (Gogoanime does not support AniList/MAL ID mapping).
 * Extraction: Routes embed URLs to GogoStreamExtractor; falls back to PlaylistUtils for m3u8.
 */
class GogoanimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Gogoanime"

    private val baseUrl = "https://gogoanime3.co"

    private val gogoHeaders by lazy {
        Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            add("Referer", "$baseUrl/")
        }.build()
    }

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // --- ID Resolution: search by title ---

    private fun searchAnime(title: String): String? {
        val url = "$baseUrl/search.html".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", title)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(gogoHeaders)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body.string()
                val document = Jsoup.parse(html)

                // Try several known selectors for robustness
                val firstResult = document.selectFirst("ul.items li p.name a")
                    ?: document.selectFirst("div.last_recent ul li p.name a")
                    ?: document.selectFirst("a[href*=/category/]")
                    ?: return null

                val href = firstResult.attr("abs:href")
                if (href.isNotBlank()) href else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- Main fetch ---

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        // Resolve: /category/slug  →  /slug-episode-N
        val categoryUrl = searchAnime(title) ?: return emptyList()
        val epUrl = categoryUrl.replace("/category/", "/") + "-episode-${meta.epNum}"

        val request = Request.Builder()
            .url(epUrl)
            .headers(gogoHeaders)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body.string()
                val document = Jsoup.parse(html)

                // Collect all server embed URLs from the multi-link div
                val serverUrls = mutableListOf<String>()

                document.select("div.anime_muti_link ul li a[data-video]").forEach { element ->
                    val dataVideo = element.attr("data-video")
                    if (dataVideo.isNotBlank()) {
                        val videoUrl = when {
                            dataVideo.startsWith("//") -> "https:$dataVideo"
                            dataVideo.startsWith("http") -> dataVideo
                            else -> "https://$dataVideo"
                        }
                        serverUrls.add(videoUrl)
                    }
                }

                // Fallback: check for iframe src
                if (serverUrls.isEmpty()) {
                    document.select("div.play-video iframe[src]").forEach { iframe ->
                        val src = iframe.attr("abs:src")
                        if (src.isNotBlank()) serverUrls.add(src)
                    }
                }

                if (serverUrls.isEmpty()) return emptyList()

                // Extract videos from all servers in parallel
                coroutineScope {
                    val deferred = serverUrls.map { videoUrl ->
                        async {
                            try {
                                when {
                                    videoUrl.contains("streaming") ||
                                    videoUrl.contains("gogo") ||
                                    videoUrl.contains("vidcloud") ||
                                    videoUrl.contains("gogocdn") -> {
                                        gogoStreamExtractor.videosFromUrl(videoUrl, name)
                                    }
                                    videoUrl.contains(".m3u8") -> {
                                        playlistUtils.extractFromHls(
                                            videoUrl, videoUrl, gogoHeaders, gogoHeaders
                                        )
                                    }
                                    else -> emptyList()
                                }
                            } catch (_: Exception) {
                                emptyList<Video>()
                            }
                        }
                    }
                    deferred.awaitAll().flatten()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
