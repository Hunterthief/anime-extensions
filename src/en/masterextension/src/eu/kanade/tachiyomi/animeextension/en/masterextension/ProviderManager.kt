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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val allAnimeApi = "https://api.allanime.day/api"
    private val jikanApi = "https://api.jikan.moe/v4"

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
            add("Accept-Language", "en-US,en;q=0.9")
            add("Content-Type", "application/json")
            add("Origin", "https://allmanga.to")
            add("Referer", "https://allmanga.to/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private val jikanHeaders by lazy {
        Headers.Builder().apply {
            add("Accept", "application/json")
            add("Accept-Language", "en-US,en;q=0.9")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }.build()
    }

    private fun makeAllAnimePostRequest(payload: JsonObject): String? {
        return try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(allAnimeApi)
                .post(body)
                .headers(allAnimeHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) return "ERR:${res.code}:${bodyStr.take(30)}"
                bodyStr
            }
        } catch (e: Exception) {
            "EXC:${e.message?.take(50)}"
        }
    }

    fun fetchAllAnimeShowId(title: String): Triple<String, String, String> {
        val query = """
            query(${'$'}search: SearchInput, ${'$'}limit: Int, ${'$'}page: Int, ${'$'}translationType: VaildTranslationTypeEnumType, ${'$'}countryOrigin: VaildCountryOriginEnumType) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page, translationType: ${'$'}translationType, countryOrigin: ${'$'}countryOrigin) {
                edges { _id name }
              }
            }
        """.trimIndent()
        
        val payload = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("search", buildJsonObject {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                })
                put("limit", 40)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            })
        }
        
        val res = makeAllAnimePostRequest(payload)
        if (res != null && !res.startsWith("ERR") && !res.startsWith("EXC")) {
            val id = res.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S1", "")
        }

        val err = res ?: "Null"
        return Triple("", "S0", err.take(60))
    }

    fun fetchAllAnimeEpisodes(showId: String): Triple<Map<String, String>, String, String> {
        // Added translationType: "sub" inside episodes argument
        val query = """
            query(${'$'}_id: String!) {
              show(_id: ${'$'}_id) {
                _id
                episodes(translationType: "sub") {
                  episodeString
                  note
                }
              }
            }
        """.trimIndent()
        
        val payload = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("_id", showId)
            })
        }
        
        val res = makeAllAnimePostRequest(payload)
        if (res != null && !res.startsWith("ERR") && !res.startsWith("EXC")) {
            val eps = res.parseAs<AllAnimeResponse>().data?.show?.episodes
            if (!eps.isNullOrEmpty()) {
                val map = eps.mapNotNull { ep ->
                    val title = ep.note?.takeIf { n -> n.isNotBlank() }
                    if (title != null) ep.episodeString to title else null
                }.toMap()
                return Triple(map, "E1", "")
            }
        }

        val err = res ?: "Null"
        return Triple(emptyMap(), "E0", err.take(60))
    }

    fun fetchJikanEpisodes(malId: Int): Triple<Map<String, String>, String, String> {
        var attempt = 0
        while (attempt < 3) {
            try {
                val request = Request.Builder()
                    .url("$jikanApi/anime/$malId/episodes")
                    .headers(jikanHeaders)
                    .get()
                    .build()
                    
                client.newCall(request).execute().use { res ->
                    val bodyStr = res.body.string()
                    
                    if (res.code == 429 || res.code in 500..599) {
                        attempt++
                        if (attempt < 3) {
                            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                            return@use
                        } else {
                            return Triple(emptyMap(), "J0", "RetryFail:${res.code}")
                        }
                    }
                    
                    if (!res.isSuccessful) return Triple(emptyMap(), "J0", "ERR:${res.code}:${bodyStr.take(30)}")
                    
                    val parsed = bodyStr.parseAs<JikanResponse>()
                    val map = parsed.data?.mapNotNull { ep ->
                        val title = ep.title?.takeIf { it.isNotBlank() }
                        if (title != null) ep.mal_id.toString() to title else null
                    }?.toMap() ?: emptyMap()
                    
                    return Triple(if (map.isNotEmpty()) map else emptyMap(), if (map.isNotEmpty()) "J1" else "J0", if (map.isNotEmpty()) "" else "Empty")
                }
            } catch (e: Exception) {
                return Triple(emptyMap(), "J0", "EXC:${e.message?.take(30)}")
            }
        }
        return Triple(emptyMap(), "J0", "MaxRetries")
    }

    // --- VIDEO EXTRACTION ---

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

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        
        return try {
            val variablesJson = buildJsonObject {
                put("showId", showId)
                put("translationType", "sub")
                put("episodeString", epNum.toString())
            }.toString()

            val extensionsJson = buildJsonObject {
                put("persistedQuery", buildJsonObject {
                    put("version", 1)
                    put("sha256Hash", "4257d8039f3e68b1c41941a7091721483d3d3050")
                })
            }.toString()

            val url = allAnimeApi.toHttpUrl().newBuilder()
                .addQueryParameter("variables", variablesJson)
                .addQueryParameter("extensions", extensionsJson)
                .build()

            val request = Request.Builder()
                .url(url)
                .headers(allAnimeHeaders)
                .get()
                .build()

            val responseBody = client.newCall(request).execute().body.string()
            val parsed = responseBody.parseAs<AllAnimeResponse>()
            val sourceUrls = parsed.data?.episode?.sourceUrls ?: emptyList()
            
            val videos = mutableListOf<Video>()
            for (source in sourceUrls) {
                val decryptedUrl = source.sourceUrl.decryptSource()
                val providerName = "AllAnime"
                
                when {
                    decryptedUrl.contains(".m3u8") -> {
                        try {
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
