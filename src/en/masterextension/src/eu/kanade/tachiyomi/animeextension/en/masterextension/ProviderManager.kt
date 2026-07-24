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
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

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
            add("Host", "api.allanime.day")
            // CRITICAL: Must be youtu-chan.com to bypass Cloudflare
            add("Origin", "https://youtu-chan.com")
            add("Referer", "https://youtu-chan.com/")
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
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
            add("Referer", "https://myanimelist.net/")
        }.build()
    }

    private fun makeAllAnimePostRequest(payload: JsonObject): String? {
        return try {
            val body = payload.toString().toJsonRequestBody()
            val request = Request.Builder()
                .url(allAnimeApi)
                .post(body)
                .headers(allAnimeHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) {
                    return "ERR:${res.code}:$bodyStr"
                }
                bodyStr
            }
        } catch (e: Exception) {
            "EXC:${e.message?.take(50)}"
        }
    }

    fun fetchAllAnimeShowId(title: String): Triple<String, String, String> {
        // Note the schema typo: VaildTranslationTypeEnumType (not Valid)
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
        // We use episodes { episodeString note } instead of availableEpisodesDetail to get titles
        val query = """
            query (${'$'}showId: String!) {
                show(_id: ${'$'}showId) {
                    _id episodes { episodeString note }
                }
            }
        """.trimIndent()
        
        val payload = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("showId", showId)
            })
        }
        
        val res = makeAllAnimePostRequest(payload)
        if (res != null && !res.startsWith("ERR") && !res.startsWith("EXC")) {
            val map = res.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Triple(map, "E1", "")
        }

        val err = res ?: "Null"
        return Triple(emptyMap(), "E0", err.take(60))
    }

    fun fetchJikanEpisodes(malId: Int): Triple<Map<String, String>, String, String> {
        return try {
            val request = Request.Builder()
                .url("$jikanApi/anime/$malId/episodes")
                .headers(jikanHeaders)
                .get()
                .build()
                
            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) return Triple(emptyMap(), "J0", "ERR:${res.code}:${bodyStr.take(30)}")
                
                val parsed = bodyStr.parseAs<JikanResponse>()
                val map = parsed.data?.associate { 
                    it.mal_id.toString() to (it.title ?: "Episode ${it.mal_id}")
                } ?: emptyMap()
                
                if (map.isNotEmpty()) Triple(map, "J1", "") else Triple(emptyMap(), "J0", "Empty")
            }
        } catch (e: Exception) {
            Triple(emptyMap(), "J0", "EXC:${e.message?.take(30)}")
        }
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        // To be implemented later using the GET Persisted Query method
        return emptyList()
    }
}
