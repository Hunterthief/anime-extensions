package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

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
        
        // FIX: Use explicit, strong browser headers to prevent CDN/Cloudflare blocking during playback
        val videoHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

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
                
                videos.add(
                    Video(
                        url = subSource,
                        quality = "$name - Sub",
                        videoUrl = subSource,
                        headers = videoHeaders,
                        subtitleTracks = subtitles
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore sub error, we will try dub next
        }

        // 2. Try to fetch DUB version
        try {
            val dubUrl = "$baseUrl/api/vidnest?al=$anilistId&ep=$epNum&lang=dub"
            val dubResp = client.newCall(GET(dubUrl, headers)).awaitSuccess()
            val dubData = json.parseToJsonElement(dubResp.body.string()).jsonObject
            val dubSource = dubData["source"]?.jsonPrimitive?.content
            
            if (!dubSource.isNullOrBlank()) {
                videos.add(
                    Video(
                        url = dubSource,
                        quality = "$name - Dub",
                        videoUrl = dubSource,
                        headers = videoHeaders
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore dub error
        }

        if (videos.isEmpty()) {
            return dbg("0 VIDEOS FOUND. Ep $epNum may not be available or API changed.")
        }

        return videos
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
