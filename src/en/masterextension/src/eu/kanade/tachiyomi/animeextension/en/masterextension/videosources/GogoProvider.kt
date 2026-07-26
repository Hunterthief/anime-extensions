package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.app.Application
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GogoProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "GogoAnime"

    companion object {
        private val DOMAINS = listOf(
            "https://anitaku.to",
            "https://gogoanime3.co",
            "https://anitaku.bz",
        )
    }

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // FIX: Manually attach CloudflareInterceptor to bypass CF challenges
    private val cfClient by lazy {
        client.newBuilder()
            .addInterceptor(CloudflareInterceptor(Injekt.get(Application::class.java), client.cookieJar, userAgent))
            .build()
    }

    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    private fun siteHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("User-Agent", userAgent)
        .removeAll("Origin")
        .build()

    private suspend fun searchAnime(title: String): Pair<String, String> {
        var lastError: Throwable? = null
        for (domain in DOMAINS) {
            try {
                val url = "$domain/search.html".toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", title)
                    .build().toString()

                // FIX: Use awaitSuccess to prevent blocking the thread
                val response = cfClient.newCall(GET(url, siteHeaders(domain))).awaitSuccess()
                val html = response.body.string()

                val doc = Jsoup.parse(html, domain)

                val firstResult = doc.selectFirst("ul.items li a[href*=/category/]")
                    ?: doc.selectFirst("div.last_recent ul li a[href*=/category/]")
                    ?: doc.selectFirst("a[href*=/category/]")

                if (firstResult != null) {
                    val href = firstResult.attr("abs:href").ifBlank { firstResult.attr("href") }
                    if (href.isNotBlank()) {
                        val fullUrl = when {
                            href.startsWith("http") -> href
                            href.startsWith("/") -> "$domain$href"
                            else -> "$domain/$href"
                        }
                        return domain to fullUrl
                    }
                }
                lastError = Exception("No results (body: ${html.take(100)})")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Search returned null")
    }

    private suspend fun getServerUrls(
        baseUrl: String,
        categoryUrl: String,
        epNum: Int,
    ): List<String> {
        val epUrl = categoryUrl.replace("/category/", "/") + "-episode-$epNum"

        val response = cfClient.newCall(GET(epUrl, siteHeaders(baseUrl))).awaitSuccess()
        val html = response.body.string()

        val doc = Jsoup.parse(html, baseUrl)
        val serverUrls = mutableListOf<String>()

        doc.select("div.anime_muti_link ul li a[data-video]").forEach { element ->
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
            doc.select("div.play-video iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                if (src.isNotBlank()) {
                    val fullUrl = when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        else -> "https:$src"
                    }
                    serverUrls.add(fullUrl)
                }
            }
        }

        return serverUrls
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        val (baseUrl, categoryUrl) = try {
            searchAnime(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        }

        val serverUrls = try {
            getServerUrls(baseUrl, categoryUrl, epNum)
        } catch (e: Exception) {
            return debugVideo("epPage threw: ${e.message}")
        }

        if (serverUrls.isEmpty()) {
            return debugVideo("0 servers on ep page for '$title' ep$epNum")
        }

        val allVideos = mutableListOf<Video>()
        val errors = mutableListOf<String>()

        serverUrls.forEach { videoUrl ->
            try {
                val videos = when {
                    videoUrl.contains("streaming") ||
                        videoUrl.contains("gogo") ||
                        videoUrl.contains("vidcloud") ||
                        videoUrl.contains("gogocdn") ||
                        videoUrl.contains("playtaku") ||
                        videoUrl.contains("playgo1") -> {
                        gogoStreamExtractor.videosFromUrl(videoUrl)
                    }
                    videoUrl.contains(".m3u8") -> {
                        playlistUtils.extractFromHls(
                            videoUrl,
                            masterHeaders = siteHeaders(baseUrl),
                            videoHeaders = siteHeaders(baseUrl),
                        )
                    }
                    else -> {
                        errors.add("unhandled:${videoUrl.take(40)}")
                        emptyList()
                    }
                }
                allVideos.addAll(videos)
            } catch (e: Exception) {
                errors.add("err:${videoUrl.take(30)}:${e.message?.take(30)}")
            }
        }

        if (allVideos.isEmpty()) {
            return debugVideo(
                "0 videos from ${serverUrls.size} servers. ${errors.take(3).joinToString(" | ")}",
            )
        }

        return allVideos
    }
}
