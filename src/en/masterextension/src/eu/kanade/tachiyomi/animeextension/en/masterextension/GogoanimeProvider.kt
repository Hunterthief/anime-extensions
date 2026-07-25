package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class GogoanimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Gogoanime"

    companion object {
        private const val BASE_URL = "https://anitaku.to"
    }

    private val gogoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private suspend fun searchAnime(title: String): String? {
        val url = "$BASE_URL/search.html".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", title)
            .build().toString()

        val html = client.newCall(GET(url, gogoHeaders)).awaitSuccess().bodyString()
        val document = Jsoup.parse(html)

        val firstResult = document.selectFirst("ul.items li p.name a")
            ?: document.selectFirst("div.last_recent ul li p.name a")
            ?: document.selectFirst("a[href*=/category/]")
            ?: return null

        val href = firstResult.attr("abs:href")
        return href.ifBlank { null }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val categoryUrl = searchAnime(title) ?: return emptyList()
        val epUrl = categoryUrl.replace("/category/", "/") + "-episode-${meta.epNum}"

        val html = client.newCall(GET(epUrl, gogoHeaders)).awaitSuccess().bodyString()
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

        if (serverUrls.isEmpty()) return emptyList()

        return serverUrls.parallelCatchingFlatMap { videoUrl ->
            when {
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
                        masterHeaders = gogoHeaders,
                        videoHeaders = gogoHeaders,
                    )
                }
                else -> emptyList()
            }
        }
    }
}
