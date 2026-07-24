package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val allAnimeApi = "https://api.allanime.day/api"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private val allAnimeHeaders by lazy {
        Headers.Builder().apply {
            add("Accept", "*/*")
            add("Content-Type", "application/json")
            add("Host", "api.allanime.day")
            add("Origin", "https://allmanga.to")
            add("Referer", "https://allmanga.to/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }.build()
    }

    private val xorKeys = arrayOf(
        "allanimenews".toCharArray(),
        "1234567890123456789".toCharArray(),
        "1234567890123456789012345".toCharArray(),
        "s5feqxw21".toCharArray(),
        "feqx1".toCharArray(),
    )

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#") -> substring(1) to 0
            else -> this to null
        }

        if (keyType == null) return this

        val key = xorKeys[keyType]
        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this
        }

        return String(CharArray(parsedChunks.size) { i ->
            ((parsedChunks[i] xor key[i % key.size].code) and 0xFF).toChar()
        })
    }

    private fun buildAllAnimePost(query: String, variables: String): Request {
        val payload = """{"query":"$query","variables":$variables}"""
        val body = payload.toRequestBody(null)
        return Request.Builder()
            .url(allAnimeApi)
            .post(body)
            .headers(allAnimeHeaders)
            .build()
    }

    fun fetchAllAnimeShowId(title: String): String {
        return try {
            val query = "query (\$search: SearchInput!) { shows(search: \$search, limit: 40, page: 1, translationType: \"SUB\", countryOrigin: \"ALL\") { edges { _id name } } }"
            val variables = """{"search":{"query":"${title.replace("\"", "\\\"")}","allowAdult":true}}"""
            val request = buildAllAnimePost(query, variables)
            client.newCall(request).execute().use { res ->
                res.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun fetchAllAnimeEpisodes(showId: String): Map<String, String> {
        return try {
            val query = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
            val variables = """{"showId":"$showId"}"""
            val request = buildAllAnimePost(query, variables)
            client.newCall(request).execute().use { res ->
                res.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate { it.episodeString to (it.note ?: "Episode ${it.episodeString}") } ?: emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        
        return try {
            val query = "query (\$showId: String!, \$translationType: String!, \$episodeString: String!) { episode(showId: \$showId, translationType: \$translationType, episodeString: \$episodeString) { sourceUrls { sourceUrl sourceName type priority } } }"
            val variables = """{"showId":"$showId","translationType":"SUB","episodeString":"$epNum"}"""
            val request = buildAllAnimePost(query, variables)
            
            val responseBody = client.newCall(request).awaitSuccess().bodyString()
            val parsed = responseBody.parseAs<AllAnimeResponse>()
            val sourceUrls = parsed.data?.episode?.sourceUrls ?: emptyList()
            
            val videos = sourceUrls.parallelCatchingFlatMap { source ->
                extractVideo(source)
            }
            
            rankVideos(videos)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun extractVideo(source: AllAnimeSourceUrl): List<Video> {
        val decryptedUrl = source.sourceUrl.decryptSource()
        val providerName = "AllAnime"
        
        return when {
            decryptedUrl.startsWith("/apivtwo/") -> {
                try {
                    val realUrl = "https://api.allanime.day$decryptedUrl"
                    val innerResponse = client.newCall(GET(realUrl, allAnimeHeaders)).awaitSuccess().bodyString()
                    val innerParsed = innerResponse.parseAs<AllAnimeApivtwoResponse>()
                    innerParsed.links.flatMap { link ->
                        val url = link.hls ?: link.link
                        if (url.isNotBlank()) {
                            try {
                                playlistUtils.extractFromHls(
                                    playlistUrl = url,
                                    referer = allAnimeHeaders["Referer"] ?: "https://allmanga.to/",
                                    videoNameGen = { quality -> "$providerName: $quality" },
                                    masterHeaders = allAnimeHeaders,
                                    videoHeaders = allAnimeHeaders
                                )
                            } catch (e: Exception) {
                                listOf(Video(url, "$providerName ${link.resolutionStr ?: "HLS"}", url, headers = allAnimeHeaders))
                            }
                        } else {
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            decryptedUrl.contains(".m3u8") -> {
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = decryptedUrl,
                        referer = allAnimeHeaders["Referer"] ?: "https://allmanga.to/",
                        videoNameGen = { quality -> "$providerName: $quality" },
                        masterHeaders = allAnimeHeaders,
                        videoHeaders = allAnimeHeaders
                    )
                } catch (e: Exception) {
                    listOf(Video(decryptedUrl, "$providerName HLS", decryptedUrl, headers = allAnimeHeaders))
                }
            }
            decryptedUrl.contains("filemoon") || decryptedUrl.contains("moon") -> {
                filemoonExtractor.videosFromUrl(decryptedUrl, "$providerName Filemoon")
            }
            decryptedUrl.contains("streamwish") || decryptedUrl.contains("wish") || decryptedUrl.contains("swhoi") -> {
                streamwishExtractor.videosFromUrl(decryptedUrl, "$providerName StreamWish")
            }
            decryptedUrl.contains("mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(decryptedUrl, allAnimeHeaders)
            }
            decryptedUrl.contains("dood") -> {
                doodExtractor.videosFromUrl(decryptedUrl)
            }
            decryptedUrl.contains("vidstreaming") || decryptedUrl.contains("gogo") || decryptedUrl.contains("vidcloud") -> {
                gogoStreamExtractor.videosFromUrl(decryptedUrl.replace(Regex("^//"), "https://"))
            }
            decryptedUrl.contains("streamlare") -> {
                streamlareExtractor.videosFromUrl(decryptedUrl)
            }
            decryptedUrl.contains("ok.ru") || decryptedUrl.contains("okru") -> {
                okruExtractor.videosFromUrl(decryptedUrl)
            }
            else -> {
                if (decryptedUrl.startsWith("http")) {
                    listOf(Video(decryptedUrl, "$providerName ${source.sourceName}", decryptedUrl, headers = allAnimeHeaders))
                } else {
                    emptyList()
                }
            }
        }
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
