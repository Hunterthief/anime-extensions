package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.SettlarProxy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AniPMProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {
    override val name = "AniPM"
    override val baseUrl = "https://ani.pm"
    
    private val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val anipmHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
            if (title.isBlank()) return dbg("Title is missing")

            // 1. Search AniPM for the Anime ID
            val animeId = searchAnimeId(title) ?: return dbg("0 results for '$title'")
            
            // 2. Get Episode Embed URL
            val embedUrl = fetchEpisodeEmbed(animeId, meta.epNum) ?: return dbg("Ep ${meta.epNum} not found")
            
            // 3. Extract Settlar Host & Qualities
            val rawVideos = extractSettlarVideos(embedUrl)
            if (rawVideos.isEmpty()) return dbg("0 videos extracted")

            // 4. Proxy through local NanoHTTPD server to bypass hotlink protection
            SettlarProxy.processVideoList(apiClient, rawVideos)
        } catch (t: Throwable) {
            dbg("${t::class.simpleName}: ${t.message?.take(100)}")
        }
    }

    private suspend fun searchAnimeId(title: String): String? {
        val url = "$baseUrl/api/search?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
        val response = apiClient.newCall(GET(url, anipmHeaders)).awaitSuccess()
        val results = response.parseAs<List<AniPMSearchResult>>()
        
        val match = results.firstOrNull { it.title.equals(title, true) } 
            ?: results.firstOrNull { it.title.contains(title, true) }
            ?: results.firstOrNull()
        return match?.id
    }

    private suspend fun fetchEpisodeEmbed(animeId: String, epNum: Int): String? {
        val url = "$baseUrl/api/anime/$animeId/episodes"
        val response = apiClient.newCall(GET(url, anipmHeaders)).awaitSuccess()
        val episodes = response.parseAs<List<AniPMEpisode>>()
        val targetEp = episodes.firstOrNull { it.number == epNum } ?: episodes.getOrNull(epNum - 1)
        return targetEp?.settlarEmbed
    }

    private suspend fun extractSettlarVideos(embedUrl: String): List<Video> {
        val response = apiClient.newCall(GET(embedUrl, anipmHeaders)).awaitSuccess()
        val document = response.useAsJsoup()
        
        // Settlar usually hides the master m3u8 in a script or data attribute
        val masterUrl = document.selectFirst("source[src*=.m3u8]")?.attr("abs:src")
            ?: document.html().substringAfter("file:\"").substringBefore("\"")
            ?: document.html().substringAfter("source: '").substringBefore("'")
            
        if (masterUrl.isBlank() || !masterUrl.contains(".m3u8")) return emptyList()

        // Fetch the m3u8 to parse qualities
        val m3u8Response = apiClient.newCall(GET(masterUrl, anipmHeaders)).awaitSuccess()
        val playlist = m3u8Response.body.string()
        
        val videos = mutableListOf<Video>()
        val regex = Regex("""RESOLUTION=\d+x(\d+).*\n(.*)""")
        regex.findAll(playlist).forEach { match ->
            val quality = match.groupValues[1]
            val streamUrl = match.groupValues[2]
            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else masterUrl.substringBeforeLast("/") + "/" + streamUrl
            videos.add(Video(absoluteUrl, "AniPM - ${quality}p", absoluteUrl))
        }
        
        // Fallback if it's a single stream playlist
        if (videos.isEmpty()) {
            videos.add(Video(masterUrl, "AniPM - Auto", masterUrl))
        }
        
        return videos
    }

    private fun dbg(msg: String): List<Video> = listOf(Video("debug://x", msg.take(120), "debug://x"))

    @Serializable
    data class AniPMSearchResult(val id: String, val title: String)
    
    @Serializable
    data class AniPMEpisode(val number: Int, val settlarEmbed: String)
}
