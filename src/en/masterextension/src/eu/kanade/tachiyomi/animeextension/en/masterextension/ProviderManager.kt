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

    private fun makeRequest(payload: JsonObject): String? {
        return try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
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
        val query = """
            query (${'$'}search: SearchInput!) {
                shows(search: ${'$'}search, limit: 40, page: 1, translationType: "sub", countryOrigin: "ALL") {
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
                    put("allowUnknown", false)
                })
            })
        }
        
        val res = makeRequest(payload)
        if (res != null && !res.startsWith("ERR") && !res.startsWith("EXC")) {
            val id = res.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S1", "")
        }

        return Triple("", "S0", res?.take(60) ?: "Null")
    }

    fun fetchAllAnimeEpisodes(showId: String): Triple<Map<String, String>, String, String> {
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
        
        val res = makeRequest(payload)
        if (res != null && !res.startsWith("ERR") && !res.startsWith("EXC")) {
            val map = res.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Triple(map, "E1", "")
        }

        return Triple(emptyMap(), "E0", res?.take(60) ?: "Null")
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        return emptyList()
    }
}
