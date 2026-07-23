package eu.kanade.tachiyomi.animeextension.en.master

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers
) {
    // Conceptual APIs representing standard aggregator backends
    private val providers = listOf(
        "https://api.consumet.org/meta/anilist",
        "https://api.hianime.zoro/meta/anilist"
    )

    suspend fun fetchVideos(anilistId: Int, episodeNumber: Int): List<Video> {
        val aggregatedVideos = mutableListOf<Video>()

        for (apiBase in providers) {
            try {
                // 1. Fetch Episode Link from Provider using AniList ID
                val episodeListUrl = "$apiBase/episodes/$anilistId"
                val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
                val episodeData = episodeResponse.parseAs<ProviderEpisodesResponse>()
                
                val targetEpisode = episodeData.episodes.firstOrNull { it.number == episodeNumber }
                val embedUrl = targetEpisode?.url ?: continue

                // 2. Fetch Servers for the Episode
                val serverUrl = "$apiBase/servers?episodeUrl=${embedUrl.toHttpUrl().encodedPath}"
                val serverResponse = client.newCall(GET(serverUrl, headers)).execute()
                val serverData = serverResponse.parseAs<ProviderServersResponse>()

                // 3. Extract Videos from each server
                for (server in serverData.servers) {
                    val videos = extractFromServer(server.url, server.name)
                    aggregatedVideos.addAll(videos)
                }
            } catch (e: Exception) {
                // Silently fail one provider and try the next to ensure maximum availability
                continue
            }
        }

        return rankVideos(aggregatedVideos)
    }

    private suspend fun extractFromServer(url: String, serverName: String): List<Video> {
        val videoList = mutableListOf<Video>()
        
        // Dynamic extraction based on URL pattern, routing to shared libraries
        when {
            url.contains(".m3u8") -> {
                // Use PlaylistUtils if available, or standard extraction
                val response = client.newCall(GET(url, headers)).execute()
                val masterPlaylist = response.body.string()
                // Parse master playlist (simplified for framework compliance)
                if (masterPlaylist.contains("#EXT-X-STREAM-INF:")) {
                    val lines = masterPlaylist.split("\n")
                    for (i in lines.indices) {
                        if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                            val quality = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1) + "p"
                            val videoUrl = lines[i+1].trim()
                            videoList.add(Video(videoUrl, "$quality ($serverName)", videoUrl))
                        }
                    }
                } else {
                    videoList.add(Video(url, "Default ($serverName)", url))
                }
            }
            url.contains("mp4upload") -> {
                // Routed to lib:mp4upload-extractor conceptually
                videoList.add(Video(url, "Mp4Upload ($serverName)", url))
            }
            else -> {
                // Routed to lib:universal-extractor conceptually
                videoList.add(Video(url, "Universal ($serverName)", url))
            }
        }

        return videoList
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
        // Rank by Quality (1080p first), then by Sub/Dub preference
        return videos.sortedWith(
            compareByDescending<Video> { 
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 
            }.thenBy { 
                it.quality.contains("Dub") 
            }
        )
    }
}

@kotlinx.serialization.Serializable
data class ProviderEpisodesResponse(
    val episodes: List<ProviderEpisode> = emptyList()
)

@kotlinx.serialization.Serializable
data class ProviderEpisode(
    val number: Int,
    val url: String
)

@kotlinx.serialization.Serializable
data class ProviderServersResponse(
    val servers: List<ProviderServer> = emptyList()
)

@kotlinx.serialization.Serializable
data class ProviderServer(
    val name: String,
    val url: String
)
