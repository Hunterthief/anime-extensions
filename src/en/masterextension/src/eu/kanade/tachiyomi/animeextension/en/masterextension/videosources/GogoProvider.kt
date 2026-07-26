package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class GogoProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "GogoAnime"

    companion object {
        // anitaku.to is dead. These are the working domains.
        private val DOMAINS = listOf(
            "https://anitaku.bz",
            "https://gogoanimehd.to",
            "https://anitaku.to",
        )
    }

    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // DEBUG HELPER — same pattern as AllAnimeProvider
    // =================================================================
    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // =================================================================
    // HEADERS
    // =================================================================
    private fun siteHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    // =================================================================
    // STEP 1: Search by title → category URL
    // =================================================================
    private suspend fun searchAnime(title: String): Pair<String, String>? {
        for (domain in DOMAINS) {
            try {
                // FIX: Use raw title as keyword — do NOT slugify
                val url = "$domain/search.html".toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", title)
                    .build().toString()

                val html = client.newCall(GET(url, siteHeaders(domain)))
                    .awaitSuccess().bodyString()

                val doc = Jsoup.parse(html)

                // FIX: Specific selector for the name link inside search results
                val firstResult = doc.selectFirst("ul.items li p.name a")
                    ?: doc.selectFirst("div.last_recent ul li p.name a")
                    ?: doc.selectFirst("a[href*=/category/]")

                if (firstResult != null) {
                    val href = firstResult.attr("abs:href")
                    if (href.isNotBlank()) {
                        return domain to href
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Get server URLs from episode page
    // =================================================================
    private suspend fun getServerUrls(
        baseUrl: String,
        categoryUrl: String,
        epNum: Int,
    ): List<String> {
        // Convert /category/slug → /slug-episode-N
        val epUrl = categoryUrl.replace("/category/", "/") + "-episode-$epNum"

        val html = client.newCall(GET(epUrl, siteHeaders(baseUrl)))
            .awaitSuccess().bodyString()

        val doc = Jsoup.parse(html)
        val serverUrls = mutableListOf<String>()

        // Primary: scan all servers with data-video attributes
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

        // Fallback: iframe embed
        if (serverUrls.isEmpty()) {
            doc.select("div.play-video iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank()) serverUrls.add(src)
            }
        }

        return serverUrls
    }

    // =================================================================
    // ENTRY POINT — DEBUG VERSION
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        // Step 1: Search
        val (baseUrl, categoryUrl) = try {
            searchAnime(title)
        } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '$title' (all ${DOMAINS.size} domains tried)")

        // Step 2: Get server URLs
        val serverUrls = try {
            getServerUrls(baseUrl, categoryUrl, epNum)
        } catch (e: Exception) {
            return debugVideo("epPage threw: ${e.message}")
        }

        if (serverUrls.isEmpty()) {
            return debugVideo("0 servers on ep page for '$title' ep$epNum")
        }

        // Step 3: Extract from each server
        val allVideos = mutableListOf<Video>()
        val errors = mutableListOf<String>()

        serverUrls.forEach { videoUrl ->
            try {
                val videos = when {
                    // GogoStreamExtractor handles: vidstreaming, gogo, vidcloud, gogocdn, playtaku, playgo1
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
