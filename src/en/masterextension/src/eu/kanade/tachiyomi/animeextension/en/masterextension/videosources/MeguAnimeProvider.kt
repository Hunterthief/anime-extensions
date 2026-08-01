package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient

class MeguAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {
    override val name = "MeguAnime"
    override val baseUrl = "https://meguanime.com"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(60)}")
        }
        val anilistId = meta.anilistId
        val epNum = meta.epNum

        val videos = mutableListOf<Video>()
        
        // Base headers for fetching the master playlist and API
        val baseHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val playlistUtils = PlaylistUtils(client, baseHeaders)

        // 1. Try to fetch SUB version
        try {
            val subUrl = "$baseUrl/api/vidnest?al=$anilistId&ep=$epNum&lang=sub"
            val subResp = client.newCall(GET(subUrl, headers)).awaitSuccess()
            val subData = json.parseToJsonElement(subResp.body.string()).jsonObject
            val subSource = subData["source"]?.jsonPrimitive?.content
            
            if (!subSource.isNullOrBlank()) {
                val subtitles = mutableListOf<Track>()
                val tracks = subData["tracks"]?.jsonArray
                if (tracks != null) {
                    for (track in tracks) {
                        val trackObj = track.jsonObject
                        val file = trackObj["file"]?.jsonPrimitive?.content
                        val label = trackObj["label"]?.jsonPrimitive?.content ?: "Unknown"
                        if (!file.isNullOrBlank()) {
                            subtitles.add(Track(file, label))
                        }
                    }
                }
                
                // FIX 1: CDNs/Workers require the Referer to be the exact m3u8 URL for segments
                // FIX 2: Add Sec-Fetch headers to bypass Cloudflare bot protection
                val segmentHeaders = Headers.Builder()
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .set("Referer", subSource) 
                    .set("Origin", baseUrl)
                    .set("Accept", "*/*")
                    .set("Sec-Fetch-Dest", "video")
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Site", "cross-site")
                    .build()

                // Diagnostic: Extract the domain to show in the quality label
                val domainSnippet = subSource.substringAfter("://").takeBefore("/").take(35)
                
                val subVideos = playlistUtils.extractFromHls(
                    subSource,
                    videoNameGen = { "$name - Sub [$domainSnippet] - $it" },
                    subtitleList = subtitles,
                    masterHeaders = baseHeaders,
                    videoHeaders = segmentHeaders
                )
                
                if (subVideos.isNotEmpty()) {
                    videos.addAll(subVideos)
                } else {
                    // Fallback if it's not an HLS stream
                    videos.add(Video(subSource, "$name - Sub (Direct) [$domainSnippet]", subSource, segmentHeaders, subtitles))
                }
            }
        } catch (e: Exception) {
            // Ignore sub error
        }

        // 2. Try to fetch DUB version
        try {
            val dubUrl = "$baseUrl/api/vidnest?al=$anilistId&ep=$epNum&lang=dub"
            val dubResp = client.newCall(GET(dubUrl, headers)).awaitSuccess()
            val dubData = json.parseToJsonElement(dubResp.body.string()).jsonObject
            val dubSource = dubData["source"]?.jsonPrimitive?.content
            
            if (!dubSource.isNullOrBlank()) {
                val segmentHeaders = Headers.Builder()
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .set("Referer", dubSource)
                    .set("Origin", baseUrl)
                    .set("Accept", "*/*")
                    .set("Sec-Fetch-Dest", "video")
                    .set("Sec-Fetch-Mode", "no-cors")
                    .set("Sec-Fetch-Site", "cross-site")
                    .build()

                val domainSnippet = dubSource.substringAfter("://").takeBefore("/").take(35)

                val dubVideos = playlistUtils.extractFromHls(
                    dubSource,
                    videoNameGen = { "$name - Dub [$domainSnippet] - $it" },
                    masterHeaders = baseHeaders,
                    videoHeaders = segmentHeaders
                )
                
                if (dubVideos.isNotEmpty()) {
                    videos.addAll(dubVideos)
                } else {
                    videos.add(Video(dubSource, "$name - Dub (Direct) [$domainSnippet]", dubSource, segmentHeaders))
                }
            }
        } catch (e: Exception) {
            // Ignore dub error
        }

        if (videos.isEmpty()) {
            return dbg("0 VIDEOS FOUND. Ep $epNum may not be available or API changed.")
        }

        return videos
    }

    private fun String.takeBefore(delimiter: String): String {
        val index = this.indexOf(delimiter)
        return if (index == -1) this else this.substring(0, index)
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
