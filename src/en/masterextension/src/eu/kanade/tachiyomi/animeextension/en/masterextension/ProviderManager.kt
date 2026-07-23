package eu.kanade.tachiyomi.animeextension.en.masterextension

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val providers = listOf(
        "https://api.consumet.org/meta/anilist",
        "https://api.hianime.zoro/meta/anilist"
    )

    suspend fun fetchVideos(anilistId: Int, episodeNumber: Int): List<Video> {
        val aggregatedVideos = mutableListOf<Video>()

        for (apiBase in providers) {
            try {
                val episodeListUrl = "$apiBase/episodes/$anilistId"
                val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
                val episodeData = json.decodeFromString<ProviderEpisodesResponse>(episodeResponse.body.string())

                val targetEpisode = episodeData.episodes.firstOrNull { it.number == episodeNumber }
                val embedUrl = targetEpisode?.url ?: continue

                val serverUrl = "$apiBase/servers?episodeUrl=${embedUrl.toHttpUrl().encodedPath}"
                val serverResponse = client.newCall(GET(serverUrl, headers)).execute()
                val serverData = json.decodeFromString<ProviderServersResponse>(serverResponse.body.string())

                for (server in serverData.servers) {
                    val videos = extractFromServer(server.url, server.name)
                    aggregatedVideos.addAll(videos)
                }
            } catch (e: Exception) {
                continue
            }
        }

        return rankVideos(aggregatedVideos)
    }

    private suspend fun extractFromServer(url: String, serverName: String): List<Video> {
        val videoList = mutableListOf<Video>()

        when {
            url.contains(".m3u8") -> {
                val response = client.newCall(GET(url, headers)).execute()
                val masterPlaylist = response.body.string()
                if (masterPlaylist.contains("#EXT-X-STREAM-INF:")) {
                    val lines = masterPlaylist.split("\n")
                    for (i in lines.indices) {
                        if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                            val res = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1) ?: "Unknown"
                            val quality = "$res p"
                            val videoUrl = lines[i + 1].trim()
                            videoList.add(Video(videoUrl, "$quality ($serverName)", videoUrl))
                        }
                    }
                } else {
                    videoList.add(Video(url, "Default ($serverName)", url))
                }
            }
            else -> {
                videoList.add(Video(url, "Unknown ($serverName)", url))
            }
        }

        return videoList
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
        return videos.sortedWith(
            compareByDescending<Video> {
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy {
                it.quality.contains("Dub")
            }
        )
    }
}
