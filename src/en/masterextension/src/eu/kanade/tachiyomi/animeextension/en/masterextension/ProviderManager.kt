package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animesource.model.Video
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
    private val consumetApi = "https://api.consumet.org/meta/anilist"

    // Providers to aggregate
    private val providers = listOf("gogoanime", "zoro", "9anime", "animepahe")

    // Initialize shared extractors
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    suspend fun fetchVideos(anilistId: Int, episodeNumber: Int): List<Video> {
        val aggregatedVideos = mutableListOf<Video>()

        // 1. Fetch Episode List from Consumet using AniList ID
        val episodeListUrl = "$consumetApi/episodes/$anilistId"
        val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
        val episodeData = json.decodeFromString<ConsumetEpisodesResponse>(episodeResponse.body.string())
        val targetEpisode = episodeData.episodes.firstOrNull { it.number == episodeNumber } ?: return emptyList()
        val episodeId = targetEpisode.id

        // 2. Query all providers in sequence and aggregate sources
        for (provider in providers) {
            try {
                val encodedEpId = java.net.URLEncoder.encode(episodeId, "UTF-8")
                val serverUrl = "$consumetApi/watch?episodeId=$encodedEpId&provider=$provider"
                val serverResponse = client.newCall(GET(serverUrl, headers)).execute()
                val serverData = json.decodeFromString<ConsumetServersResponse>(serverResponse.body.string())

                for (source in serverData.sources) {
                    val url = source.url
                    val srcHeaders = Headers.Builder().apply {
                        serverData.headers.forEach { (k, v) -> add(k, v) }
                    }.build()

                    // 3. Route URLs to the appropriate shared library extractor
                    when {
                        url.contains(".m3u8") && source.isM3U8 == true -> {
                            try {
                                aggregatedVideos.addAll(playlistUtils.extractFromHls(url, srcHeaders, url))
                            } catch (e: Exception) {
                                aggregatedVideos.add(Video(url, "$provider ${source.quality ?: "HLS"}", url, headers = srcHeaders))
                            }
                        }
                        url.contains("filemoon") || url.contains("moon") -> {
                            aggregatedVideos.addAll(filemoonExtractor.videosFromUrl(url, "$provider Filemoon"))
                        }
                        url.contains("streamwish") || url.contains("wish") || url.contains("swhoi") -> {
                            aggregatedVideos.addAll(streamwishExtractor.videosFromUrl(url, "$provider StreamWish"))
                        }
                        url.contains("mp4upload") -> {
                            // Mp4uploadExtractor in yuzono expects a String prefix
                            aggregatedVideos.addAll(mp4uploadExtractor.videosFromUrl(url, "$provider Mp4Upload"))
                        }
                        url.contains("dood") -> {
                            aggregatedVideos.addAll(doodExtractor.videosFromUrl(url))
                        }
                        url.contains("vidhide") || url.contains("streamtape") -> {
                            aggregatedVideos.addAll(vidHideExtractor.videosFromUrl(url))
                        }
                        url.contains("vidmoly") -> {
                            aggregatedVideos.addAll(vidMolyExtractor.videosFromUrl(url))
                        }
                        url.contains("streamlare") -> {
                            aggregatedVideos.addAll(streamlareExtractor.videosFromUrl(url))
                        }
                        url.contains("ok.ru") || url.contains("okru") -> {
                            aggregatedVideos.addAll(okruExtractor.videosFromUrl(url))
                        }
                        else -> {
                            aggregatedVideos.add(Video(url, "$provider ${source.quality ?: "Unknown"}", url, headers = srcHeaders))
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently fail one provider and try the next
                continue
            }
        }

        return rankVideos(aggregatedVideos)
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
        val preferredSubType = preferences.getString("preferred_sub_type", "softsub") ?: "softsub"

        return videos.sortedWith(
            compareByDescending<Video> {
                // Rank by resolution
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy {
                // Rank by preferred subtitle type
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
