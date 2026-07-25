package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class GogoanimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Gogoanime"

    // Updated domain
    private val baseUrl = "https://gogoanimehd.to"

    private val gogoHeaders by lazy {
        Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            add("Referer", "$baseUrl/")
        }.build()
    }

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private suspend fun searchAnime(title: String): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/search.html".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", title)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(gogoHeaders)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body.string()
                val document = Jsoup.parse(html)

                val firstResult = document.selectFirst("ul.items li p.name a")
                    ?: document.selectFirst("div.last_recent ul li p.name a")
                    ?: document.selectFirst("a[href*=/category/]")
                    ?: return@withContext null

                val href = firstResult.attr("abs:href")
                if (href.isNotBlank()) href else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val categoryUrl = searchAnime(title) ?: return emptyList()
        val epUrl = categoryUrl.replace("/category/", "/") + "-episode-${meta.epNum}"

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(epUrl)
                .headers(gogoHeaders)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val html = response.body.string()
                    val document = Jsoup.parse(html)

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

                    if (serverUrls.isEmpty()) {
                        document.select("div.play-video iframe[src]").forEach { iframe ->
                            val src = iframe.attr("abs:src")
                            if (src.isNotBlank()) serverUrls.add(src)
                        }
                    }

                    if (serverUrls.isEmpty()) return@withContext emptyList()

                    coroutineScope {
                        val deferred = serverUrls.map { videoUrl ->
                            async {
                                try {
                                    when {
                                        videoUrl.contains("streaming") ||
                                        videoUrl.contains("gogo") ||
                                        videoUrl.contains("vidcloud") ||
                                        videoUrl.contains("gogocdn") -> {
                                            gogoStreamExtractor.videosFromUrl(videoUrl)
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
}
