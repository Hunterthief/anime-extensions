package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animenosub.MoonExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animenosub.VtubeExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animenosub.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder

class AnimenosubProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "Animenosub"

    companion object {
        private const val BASE_URL = "https://animenosub.to"
    }

    private val siteHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()
    }

    private val moonExtractor by lazy { MoonExtractor(client, headers, BASE_URL) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client) }
    private val vtubeExtractor by lazy { VtubeExtractor(client, headers) }
    private val wolfstreamExtractor by lazy { WolfstreamExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // DEBUG HELPER
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
    // STEP 1: Search by title
    // =================================================================
    private suspend fun searchAnime(title: String): String? {
        val url = "$BASE_URL/?s=${URLEncoder.encode(title, "UTF-8")}"
        val doc = try {
            client.newCall(GET(url, siteHeaders)).awaitSuccess().asJsoup()
        } catch (_: Exception) {
            return null
        }
        
        // AnimeStream search results usually use div.bsx > a or article.bs > a
        val results = doc.select("div.bsx > a, article.bs > a, div.item > a, div.poster > a")
        val cleanTitle = title.trim().lowercase()
        
        val match = results.firstOrNull { 
            val text = it.text().trim().lowercase()
            text == cleanTitle || text.contains(cleanTitle) || cleanTitle.contains(text)
        } ?: results.firstOrNull()
        
        return match?.attr("abs:href")
    }

    // =================================================================
    // STEP 2: Get episode URL
    // =================================================================
    private suspend fun getEpisodeUrl(animeUrl: String, epNum: Int): String? {
        val doc = try {
            client.newCall(GET(animeUrl, siteHeaders)).awaitSuccess().asJsoup()
        } catch (_: Exception) {
            return null
        }
        
        // AnimeStream episode list: div.eplister ul li a
        val episodes = doc.select("div.eplister ul li a, div.eplister li a, ul.eplister li a")
        val epRegex = Regex("""(?:Ep\.?|Episode)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("""/episode[-/](\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        
        val match = episodes.firstOrNull { 
            val text = it.text().trim()
            epRegex.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() == epNum.toFloat()
        } ?: episodes.firstOrNull {
            val href = it.attr("href")
            hrefRegex.find(href)?.groupValues?.getOrNull(1)?.toFloatOrNull() == epNum.toFloat()
        }
        
        return match?.attr("abs:href")
    }

    // =================================================================
    // STEP 3: Extract embed URLs from episode page
    // =================================================================
    private suspend fun getEmbedUrls(episodeUrl: String): List<String> {
        val doc = try {
            client.newCall(GET(episodeUrl, siteHeaders)).awaitSuccess().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }
        
        // Standard iframes
        val iframes = doc.select("iframe[src]")
        val embedUrls = iframes.map { it.attr("abs:src") }.filter { it.isNotBlank() }
        
        // AnimeStream mirror dropdowns often use data-video
        val dataVideos = doc.select("[data-video]").map { it.attr("abs:data-video") }.filter { it.isNotBlank() }
        
        return (embedUrls + dataVideos).distinct()
    }

    // =================================================================
    // STEP 4: Extract videos from embed URLs
    // =================================================================
    private suspend fun extractVideos(embedUrls: List<String>): List<Video> {
        val allVideos = mutableListOf<Video>()
        
        for (url in embedUrls) {
            val host = url.toHttpUrl().host
            val prefix = "Animenosub - "
            
            try {
                val videos = when {
                    listOf("bysesayeveum", "filemoon", "fmoon", "moonembed", "moon").any { host.contains(it) } -> {
                        moonExtractor.videosFromUrl(url, prefix)
                    }
                    host.contains("vidmoly") -> {
                        vidMolyExtractor.videosFromUrl(url, prefix)
                    }
                    listOf("streamwish", "swdyu").any { host.contains(it) } -> {
                        val wishHeaders = headers.newBuilder().set("Referer", "$BASE_URL/").build()
                        streamWishExtractor.videosFromUrl(url, prefix, wishHeaders)
                    }
                    listOf("vtbe", "vtube").any { host.contains(it) } -> {
                        vtubeExtractor.videosFromUrl(url, BASE_URL, prefix)
                    }
                    host.contains("wolfstream") -> {
                        wolfstreamExtractor.videosFromUrl(url, prefix)
                    }
                    else -> {
                        // Fallback: try PlaylistUtils if it's a direct m3u8/mp4
                        if (url.contains(".m3u8") || url.contains(".mp4")) {
                            playlistUtils.extractFromHls(
                                url,
                                referer = url,
                                videoNameGen = { "$prefix $it" }
                            )
                        } else {
                            emptyList()
                        }
                    }
                }
                allVideos.addAll(videos)
            } catch (_: Exception) {
                // Skip failed extractors
            }
        }
        
        return allVideos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")
        
        val epNum = if (meta.epNum > 0) meta.epNum else 1
        
        val animeUrl = try { searchAnime(title) } catch (e: Exception) {
            return debugVideo("search threw: ${e.message}")
        } ?: return debugVideo("search null for '$title'")
        
        val episodeUrl = try { getEpisodeUrl(animeUrl, epNum) } catch (e: Exception) {
            return debugVideo("getEpisodeUrl threw: ${e.message}")
        } ?: return debugVideo("no episode $epNum found for '$title'")
        
        val embedUrls = try { getEmbedUrls(episodeUrl) } catch (e: Exception) {
            return debugVideo("getEmbedUrls threw: ${e.message}")
        }
        
        if (embedUrls.isEmpty()) {
            return debugVideo("no embed URLs found on episode page")
        }
        
        val videos = try { extractVideos(embedUrls) } catch (e: Exception) {
            return debugVideo("extractVideos threw: ${e.message}")
        }
        
        if (videos.isEmpty()) {
            return debugVideo("0 videos extracted from ${embedUrls.size} embeds")
        }
        
        return videos
    }
}
