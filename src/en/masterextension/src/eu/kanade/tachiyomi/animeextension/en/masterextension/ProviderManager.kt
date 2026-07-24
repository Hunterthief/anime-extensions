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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

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
            add("Host", "api.allanime.day")
            add("Origin", "https://allmanga.to")
            add("Referer", "https://allmanga.to/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }.build()
    }

    private fun makeAllAnimeRequest(query: String, variables: String): String? {
        return try {
            // Must use GET with URL-encoded JSON payload
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val encodedVariables = URLEncoder.encode(variables, "UTF-8")
            val url = "$allAnimeApi?variables=$encodedVariables&query=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .headers(allAnimeHeaders)
                .get()
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
        // --- STRATEGY 1: SearchInput Object & Translation Variable ---
        val query1 = "query (\$search: SearchInput!, \$limit: Int, \$page: Int, \$translationType: String, \$countryOrigin: String) { shows(search: \$search, limit: \$limit, page: \$page, translationType: \$translationType, countryOrigin: \$countryOrigin) { edges { _id name } } }"
        val variables1 = """{"search":{"query":"$title","allowAdult":true,"allowUnknown":true},"limit":40,"page":1,"translationType":"sub","countryOrigin":"ALL"}"""
        
        val res1 = makeAllAnimeRequest(query1, variables1)
        if (res1 != null && !res1.startsWith("ERR") && !res1.startsWith("EXC")) {
            val id = res1.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S1", "")
        }

        // --- STRATEGY 2: Raw String Search ---
        val query2 = "query (\$search: String!, \$translationType: String, \$countryOrigin: String) { shows(search: \$search, limit: 40, page: 1, translationType: \$translationType, countryOrigin: \$countryOrigin) { edges { _id name } } }"
        val variables2 = """{"search":"$title","translationType":"sub","countryOrigin":"ALL"}"""
        
        val res2 = makeAllAnimeRequest(query2, variables2)
        if (res2 != null && !res2.startsWith("ERR") && !res2.startsWith("EXC")) {
            val id = res2.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S2", "")
        }

        val err = res1 ?: res2 ?: "Null"
        return Triple("", "S0", err.take(60))
    }

    fun fetchAllAnimeEpisodes(showId: String): Triple<Map<String, String>, String, String> {
        val query1 = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
        val variables1 = """{"showId":"$showId"}"""
        
        val res1 = makeAllAnimeRequest(query1, variables1)
        if (res1 != null && !res1.startsWith("ERR") && !res1.startsWith("EXC")) {
            val map = res1.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Triple(map, "E1", "")
        }

        val err = res1 ?: "Null"
        return Triple(emptyMap(), "E0", err.take(60))
    }

    fun fetchJikanEpisodes(malId: Int): Triple<Map<String, String>, String, String> {
        return try {
            val request = Request.Builder()
                .url("$jikanApi/anime/$malId/episodes")
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
        return emptyList()
    }
}
