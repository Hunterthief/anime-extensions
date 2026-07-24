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
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

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
            add("Referer", "https://allmanga.to/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }.build()
    }

    // XOR keys indexed by source-URL prefix type: '--'=3 '#-'=2 '##'=1 '-#'=4 '#'=0
    private val xorKeys = arrayOf(
        "allanimenews",
        "1234567890123456789",
        "1234567890123456789012345",
        "s5feqxw21",
        "feqx1",
    )

    // Pre-compute cumulative XOR mask for each key (XOR of all char codes)
    private val xorMasks = xorKeys.map { key ->
        key.fold(0) { mask, ch -> mask xor ch.code }
    }.toIntArray()

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#") -> substring(1) to 0
            else -> this to null
        }

        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this
        }

        if (keyType == null) {
            xorMasks.forEach { mask ->
                val decrypted = String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
                if (decrypted.contains("/clock") || decrypted.contains("http")) return decrypted
            }
            return this
        }

        val mask = xorMasks[keyType]
        return String(CharArray(parsedChunks.size) { i -> ((parsedChunks[i] xor mask) and 0xFF).toChar() })
    }

    suspend fun fetchAllAnimeShowId(title: String): String {
        return try {
            val query = "query (\$search: String!) { shows(search: \$search, allowAdult: true) { edges { _id name } } }"
            val variables = buildJsonObject { put("search", title) }
            val request = graphQLPost(allAnimeApi, allAnimeHeaders, query, variables = variables)
            val response = client.newCall(request).execute()
            response.parseGraphQLAs<AllAnimeSearchData>().shows?.edges?.firstOrNull()?._id ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun fetchAllAnimeEpisodes(showId: String): Map<String, String> {
        return try {
            val query = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
            val variables = buildJsonObject { put("showId", showId) }
            val request = graphQLPost(allAnimeApi, allAnimeHeaders, query, variables = variables)
            val response = client.newCall(request).execute()
            response.parseGraphQLAs<AllAnimeShowData>().show?.episodes?.associate { it.episodeString to (it.note ?: "Episode ${it.episodeString}") } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        
        return try {
            val query = "query (\$showId: String!, \$translationType: String!, \$episodeString: String!) { episode(showId: \$showId, translationType: \$translationType, episodeString: \$episodeString) { sourceUrls { sourceUrl sourceName type priority } } }"
            val variables = buildJsonObject {
                put("showId", showId)
                put("translationType", "SUB")
                put("episodeString", epNum.toString())
            }
            val request = graphQLPost(allAnimeApi, allAnimeHeaders, query, variables = variables)
            val response = client.newCall(request).execute()
            val sources = response.parseGraphQLAs<AllAnimeEpisodeData>().episode?.sourceUrls ?: emptyList()
            
            val videos = mutableListOf<Video>()
            for (source in sources) {
                val decryptedUrl = source.sourceUrl.decryptSource()
                
                if (decryptedUrl.startsWith("/apivtwo/")) continue // Skip internal hosters for stability
                
                val providerName = "AllAnime"
                when {
                    decryptedUrl.contains(".m3u8") -> {
                        try {
                            // FIX: Correct signature for extractFromHls
                            videos.addAll(playlistUtils.extractFromHls(decryptedUrl, decryptedUrl, allAnimeHeaders, allAnimeHeaders))
                        } catch (e: Exception) {
                            videos.add(Video(decryptedUrl, "$providerName HLS", decryptedUrl, headers = allAnimeHeaders))
                        }
                    }
                    decryptedUrl.contains("filemoon") || decryptedUrl.contains("moon") -> {
                        videos.addAll(filemoonExtractor.videosFromUrl(decryptedUrl, "$providerName Filemoon"))
                    }
                    decryptedUrl.contains("streamwish") || decryptedUrl.contains("wish") || decryptedUrl.contains("swhoi") -> {
                        videos.addAll(streamwishExtractor.videosFromUrl(decryptedUrl, "$providerName StreamWish"))
                    }
                    decryptedUrl.contains("mp4upload") -> {
                        // FIX: Correct signature for videosFromUrl
                        videos.addAll(mp4uploadExtractor.videosFromUrl(decryptedUrl, allAnimeHeaders))
                    }
                    decryptedUrl.contains("dood") -> {
                        videos.addAll(doodExtractor.videosFromUrl(decryptedUrl))
                    }
                    decryptedUrl.contains("vidstreaming") || decryptedUrl.contains("gogo") || decryptedUrl.contains("vidcloud") -> {
                        videos.addAll(gogoStreamExtractor.videosFromUrl(decryptedUrl.replace(Regex("^//"), "https://")))
                    }
                    decryptedUrl.contains("streamlare") -> {
                        videos.addAll(streamlareExtractor.videosFromUrl(decryptedUrl))
                    }
                    decryptedUrl.contains("ok.ru") || decryptedUrl.contains("okru") -> {
                        videos.addAll(okruExtractor.videosFromUrl(decryptedUrl))
                    }
                    else -> {
                        if (decryptedUrl.startsWith("http")) {
                            videos.add(Video(decryptedUrl, "$providerName ${source.sourceName}", decryptedUrl, headers = allAnimeHeaders))
                        }
                    }
                }
            }
            rankVideos(videos)
        } catch (e: Exception) {
            emptyList()
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
