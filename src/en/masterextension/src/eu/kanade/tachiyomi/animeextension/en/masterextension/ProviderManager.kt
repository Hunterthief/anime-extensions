package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val consumetApi = preferences.getString("consumet_api_url", "https://api.consumet.org/meta/anilist") ?: "https://api.consumet.org/meta/anilist"
    private val consumetProviders = listOf("gogoanime", "zoro", "9anime", "animepahe")

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    suspend fun fetchVideos(episodeUrl: String): List<Video> {
        // If it contains a slash, it's a fallback (AniList ID / Ep Number)
        return if (episodeUrl.contains("/")) {
            val parts = episodeUrl.split("/")
            val anilistId = parts[0].toIntOrNull() ?: return emptyList()
            val episodeNumber = parts[1].toFloatOrNull() ?: 1f
            fetchFromConsumetByNumber(anilistId, episodeNumber)
        } else {
            // It's a direct Consumet Episode ID
            fetchFromConsumetById(episodeUrl)
        }
    }

    private suspend fun fetchFromConsumetById(episodeId: String): List<Video> = coroutineScope {
        val deferredVideos = consumetProviders.map { provider ->
            async {
                try {
                    val encodedEpId = java.net.URLEncoder.encode(episodeId, "UTF-8")
                    val serverUrl = "$consumetApi/watch/$encodedEpId?provider=$provider"
                    val serverResponse = client.newCall(GET(serverUrl, headers)).execute()
                    val serverData = json.decodeFromString<ConsumetServersResponse>(serverResponse.body.string())

                    extractFromSourceList(serverData.sources, serverData.headers, provider)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        return@coroutineScope deferredVideos.awaitAll().flatten()
    }

    private suspend fun fetchFromConsumetByNumber(anilistId: Int, episodeNumber: Float): List<Video> = coroutineScope {
        try {
            val episodeListUrl = "$consumetApi/episodes/$anilistId"
            val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
            val episodeData = json.decodeFromString<List<ConsumetEpisode>>(episodeResponse.body.string())
            val targetEpisode = episodeData.firstOrNull { it.number == episodeNumber } 
                ?: return@coroutineScope emptyList()
            
            return@coroutineScope fetchFromConsumetById(targetEpisode.id)
        } catch (e: Exception) {
            return@coroutineScope emptyList()
        }
    }

    private suspend fun extractFromSourceList(
        sources: List<ConsumetSource>,
        headersMap: Map<String, String>,
        providerName: String
    ): List<Video> {
        val videos = mutableListOf<Video>()
        for (source in sources) {
            val url = source.url
            val srcHeaders = Headers.Builder().apply {
                headersMap.forEach { (k, v) -> add(k, v) }
            }.build()

            when {
                url.contains(".m3u8") && source.isM3U8 == true -> {
                    try {
                        val m3u8Response = client.newCall(GET(url, srcHeaders)).execute()
                        val masterPlaylist = m3u8Response.body.string()
                        if (masterPlaylist.contains("#EXT-X-STREAM-INF:")) {
                            val lines = masterPlaylist.split("\n")
                            for (i in lines.indices) {
                                if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                                    val res = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1) ?: "Unknown"
                                    val quality = "$providerName $res p"
                                    val videoUrl = lines[i + 1].trim()
                                    videos.add(Video(videoUrl, quality, videoUrl, headers = srcHeaders))
                                }
                            }
                        } else {
                            videos.add(Video(url, "$providerName ${source.quality ?: "HLS"}", url, headers = srcHeaders))
                        }
                    } catch (e: Exception) {
                        videos.add(Video(url, "$providerName ${source.quality ?: "HLS"}", url, headers = srcHeaders))
                    }
                }
                url.contains("filemoon") || url.contains("moon") -> {
                    videos.addAll(filemoonExtractor.videosFromUrl(url, "$providerName Filemoon"))
                }
                url.contains("streamwish") || url.contains("wish") || url.contains("swhoi") -> {
                    videos.addAll(streamwishExtractor.videosFromUrl(url, "$providerName StreamWish"))
                }
                url.contains("mp4upload") -> {
                    videos.addAll(mp4uploadExtractor.videosFromUrl(url, headers))
                }
                url.contains("dood") -> {
                    videos.addAll(doodExtractor.videosFromUrl(url))
                }
                url.contains("vidhide") || url.contains("streamtape") -> {
                    videos.addAll(vidHideExtractor.videosFromUrl(url))
                }
                url.contains("vidmoly") -> {
                    videos.addAll(vidMolyExtractor.videosFromUrl(url))
                }
                url.contains("streamlare") -> {
                    videos.addAll(streamlareExtractor.videosFromUrl(url))
                }
                url.contains("ok.ru") || url.contains("okru") -> {
                    videos.addAll(okruExtractor.videosFromUrl(url))
                }
                else -> {
                    videos.add(Video(url, "$providerName ${source.quality ?: "Unknown"}", url, headers = srcHeaders))
                }
            }
        }
        return videos
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
        val preferredSubType = preferences.getString("preferred_sub_type", "softsub") ?: "softsub"

        return videos.sortedWith(
            compareByDescending<Video> {
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy {
                when {
                    it.quality.contains(preferredSubType, ignoreCase = true) -> 0
                    it.quality.contains("softsub", ignoreCase = true) -> 1
                    it.quality.contains("hardsub", ignoreCase = true) -> 2
                    it.quality.contains("dub", ignoreCase = true) -> 3
                    else -> 4
                }
            }
        )
    }
}
