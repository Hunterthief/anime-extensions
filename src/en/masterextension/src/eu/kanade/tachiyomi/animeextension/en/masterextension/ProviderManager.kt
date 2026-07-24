package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import android.util.Log
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

    private fun makeRequest(payload: String): String? {
        return try {
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(allAnimeApi)
                .post(body)
                .headers(allAnimeHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.e("MasterExt", "HTTP ${res.code}: ${res.body.string()}")
                    return null
                }
                res.body.string()
            }
        } catch (e: Exception) {
            Log.e("MasterExt", "Request Exception", e)
            null
        }
    }

    fun fetchAllAnimeShowId(title: String): Pair<String, String> {
        val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")

        // --- STRATEGY 1: Raw String Payload ---
        val payload1 = """{"query":"query (\$search: SearchInput!) { shows(search: \$search, limit: 40, page: 1, translationType: \"SUB\", countryOrigin: \"ALL\") { edges { _id name } } }","variables":{"search":{"query":"$escapedTitle","allowAdult":true,"allowUnknown":true}}}"""
        makeRequest(payload1)?.let { body ->
            val id = body.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Pair(id, "S1")
        }

        // --- STRATEGY 2: kotlinx.serialization JsonObject ---
        val query2 = "query (\$search: SearchInput!) { shows(search: \$search, limit: 40, page: 1, translationType: \"SUB\", countryOrigin: \"ALL\") { edges { _id name } } }"
        val payload2 = buildJsonObject {
            put("query", query2)
            put("variables", buildJsonObject {
                put("search", buildJsonObject {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                })
            })
        }.toString()
        makeRequest(payload2)?.let { body ->
            val id = body.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Pair(id, "S2")
        }

        // --- STRATEGY 3: Primitives in Variables ---
        val query3 = "query (\$search: String!, \$translation: String!, \$country: String!) { shows(search: {query: \$search, allowAdult: true, allowUnknown: true}, limit: 40, page: 1, translationType: \$translation, countryOrigin: \$country) { edges { _id name } } }"
        val payload3 = buildJsonObject {
            put("query", query3)
            put("variables", buildJsonObject {
                put("search", title)
                put("translation", "SUB")
                put("country", "ALL")
            })
        }.toString()
        makeRequest(payload3)?.let { body ->
            val id = body.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Pair(id, "S3")
        }

        return Pair("", "S0")
    }

    fun fetchAllAnimeEpisodes(showId: String): Pair<Map<String, String>, String> {
        // --- STRATEGY 1: Raw String Payload ---
        val payload1 = """{"query":"query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }","variables":{"showId":"$showId"}}"""
        makeRequest(payload1)?.let { body ->
            val map = body.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Pair(map, "E1")
        }

        // --- STRATEGY 2: kotlinx.serialization JsonObject ---
        val query2 = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
        val payload2 = buildJsonObject {
            put("query", query2)
            put("variables", buildJsonObject {
                put("showId", showId)
            })
        }.toString()
        makeRequest(payload2)?.let { body ->
            val map = body.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Pair(map, "E2")
        }

        return Pair(emptyMap(), "E0")
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        // To be implemented later
        return emptyList()
    }
}
