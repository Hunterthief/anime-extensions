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
import eu.kanade.tachiyomi.network.POST
import keiyoushi.lib.unpacker.Unpacker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val consumetApi = preferences.getString("consumet_api_url", "https://api.consumet.org/meta/anilist") ?: "https://api.consumet.org/meta/anilist"
    private val allAnimeApi = "https://api.allmanga.to/api"
    
    private val consumetProviders = listOf("gogoanime", "zoro", "9anime", "animepahe")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    suspend fun fetchVideos(anilistId: Int, target: String, title: String): List<Video> = coroutineScope {
        val epNum = target.toFloatOrNull() ?: 1f
        val videos = mutableListOf<Video>()
        
        // 1. Try AllAnime (Highly reliable)
        videos.addAll(fetchFromAllAnime(title, epNum))
        
        // 2. Try Consumet
        if (videos.isEmpty()) {
            videos.addAll(fetchFromConsumetByNumber(anilistId, epNum))
        }
        
        return@coroutineScope rankVideos(videos)
    }

    private suspend fun fetchFromAllAnime(title: String, episodeNumber: Float): List<Video> = coroutineScope {
        try {
            val searchQuery = "query (\$search: String!) { shows(search: \$search) { edges { _id name } } }"
            val searchVars = """{"search":"${title.replace("\"", "\\\"")}"}"""
            val searchPayload = """{"query":"$searchQuery","variables":$searchVars}"""
            val searchResponse = client.newCall(POST(allAnimeApi, headers, searchPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))).execute()
            val searchData = json.decodeFromString<AllAnimeSearchResponse>(searchResponse.body.string())
            val showId = searchData.data?.shows?.edges?.firstOrNull()?._id ?: return@coroutineScope emptyList()
            
            val epQuery = "query (\$showId: String!, \$episodeString: String!, \$translationType: String!, \$source: String!) { episode(showId: \$showId, episodeString: \$episodeString, translationType: \$translationType, source: \$source) { sourceUrls } }"
            val sourcesToQuery = listOf("gogoanime", "zoro", "animepahe")
            
            val deferredVideos = sourcesToQuery.map { source ->
                async {
                    try {
                        val epVars = """{"showId":"$showId","episodeString":"${episodeNumber.toInt()}","translationType":"SUB","source":"$source"}"""
                        val epPayload = """{"query":"$epQuery","variables":$epVars}"""
                        val epResponse = client.newCall(POST(allAnimeApi, headers, epPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))).execute()
                        val epData = json.decodeFromString<AllAnimeEpisodeResponse>(epResponse.body.string())
                        val sources = epData.data?.episode?.sourceUrls ?: emptyList()
                        
                        val vids = mutableListOf<Video>()
                        for (url in sources) {
                            vids.addAll(extractFromAllAnimeUrl(url, source))
                        }
                        vids
                    } catch (e: Exception) {
                        emptyList<Video>()
                    }
                }
            }
            return@coroutineScope deferredVideos.awaitAll().flatten()
        } catch (e: Exception) {
            return@coroutineScope emptyList()
        }
    }

    private suspend fun extractFromAllAnimeUrl(url: String, providerName: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            
            val unpacked = if (body.contains("eval(function(p,a,c,k,e,d)")) {
                Unpacker.unpack(body)
            } else {
                body
            }
            
            val sourceRegex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            sourceRegex.findAll(unpacked).forEach { match ->
                val srcUrl = match.groupValues[1]
                if (srcUrl.contains(".m3u8")) {
                    try {
                        videos.addAll(playlistUtils.extractFromHls(srcUrl, url))
                    } catch (e: Exception) {
                        videos.add(Video(srcUrl, "$providerName AllAnime", srcUrl, headers = headers))
                    }
                } else {
                    videos.add(Video(srcUrl, "$providerName AllAnime MP4", srcUrl, headers = headers))
                }
            }
            
            if (videos.isEmpty()) {
                videos.addAll(extractFromSourceList(listOf(ConsumetSource(url = url)), emptyMap(), providerName))
            }
        } catch (e: Exception) {
            // Ignore
        }
        return videos
    }

    private suspend fun fetchFromConsumetByNumber(anilistId: Int, episodeNumber: Float): List<Video> = coroutineScope {
        try {
            val episodeListUrl = "$consumetApi/episodes/$anilistId"
            val episodeResponse = client.newCall(GET(episodeListUrl, headers)).execute()
            val episodeData = json.decodeFromString<List<ConsumetEpisode>>(episodeResponse.body.string())
            val targetEpisode = episodeData.firstOrNull { it.number.toInt() == episodeNumber.toInt() } 
                ?: return@coroutineScope emptyList()
            
            val deferredVideos = consumetProviders.map { provider ->
                async {
                    try {
                        val encodedEpId = java.net.URLEncoder.encode(targetEpisode.id, "UTF-8")
                        val serverUrl = "$consumetApi/watch?episodeId=$encodedEpId&provider=$provider"
                        val response = client.newCall(GET(serverUrl, headers)).execute()
                        val data = json.decodeFromString<ConsumetServersResponse>(response.body.string())
                        extractFromSourceList(data.sources, data.headers, provider)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            return@coroutineScope deferredVideos.awaitAll().flatten()
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
                        videos.addAll(playlistUtils.extractFromHls(url, url))
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
