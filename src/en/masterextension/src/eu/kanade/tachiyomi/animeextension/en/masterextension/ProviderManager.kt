package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Consumet API instance
    private val consumetApi = "https://api.consumet.org/meta/anilist"

    // Initialize extractors
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    suspend fun fetchVideos(anilistId: Int, episodeNumber: Int): List<Video> {
        val aggregatedVideos = mutableListOf<Video>()

        try {
            // 1. Fetch Episode List from Consumet using AniList ID
            val episodeListUrl = "$consumetApi/episodes/$anilistId"
            val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
            val episodeData = json.decodeFromString<ConsumetEpisodesResponse>(episodeResponse.body.string())

            // 2. Find the target episode ID
            val targetEpisode = episodeData.episodes.firstOrNull { it.number == episodeNumber } ?: return emptyList()
            val episodeId = targetEpisode.id

            // 3. Fetch Servers and Sources for the Episode
            val encodedEpId = java.net.URLEncoder.encode(episodeId, "UTF-8")
            val serverUrl = "$consumetApi/watch?episodeId=$encodedEpId&provider=gogoanime"
            val serverResponse = client.newCall(GET(serverUrl, headers)).execute()
            val serverData = json.decodeFromString<ConsumetServersResponse>(serverResponse.body.string())

            // 4. Extract Videos from sources
            for (source in serverData.sources) {
                val url = source.url
                if (url.contains(".m3u8") && source.isM3U8 == true) {
                    // Manual m3u8 parsing
                    val m3u8Response = client.newCall(GET(url, headers)).execute()
                    val masterPlaylist = m3u8Response.body.string()
                    if (masterPlaylist.contains("#EXT-X-STREAM-INF:")) {
                        val lines = masterPlaylist.split("\n")
                        for (i in lines.indices) {
                            if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                                val res = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1) ?: "Unknown"
                                val quality = "$res p"
                                val videoUrl = lines[i + 1].trim()
                                aggregatedVideos.add(Video(videoUrl, quality, videoUrl))
                            }
                        }
                    } else {
                        aggregatedVideos.add(Video(url, source.quality ?: "Unknown (m3u8)", url))
                    }
                } else if (url.contains("filemoon") || url.contains("moon")) {
                    aggregatedVideos.addAll(filemoonExtractor.videosFromUrl(url, "Filemoon"))
                } else if (url.contains("streamwish") || url.contains("wish") || url.contains("swhoi")) {
                    aggregatedVideos.addAll(streamwishExtractor.videosFromUrl(url, "StreamWish"))
                } else {
                    aggregatedVideos.add(Video(url, source.quality ?: "Unknown", url))
                }
            }
        } catch (e: Exception) {
            return aggregatedVideos
        }

        return rankVideos(aggregatedVideos)
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
