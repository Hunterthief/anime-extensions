package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import android.net.Uri
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

    private fun makeRequest(query: String, variables: String): String? {
        return try {
            // AllAnime requires the payload in the URL query parameters, not the body!
            val url = Uri.parse(allAnimeApi).buildUpon()
                .appendQueryParameter("variables", variables)
                .appendQueryParameter("query", query)
                .build()
                .toString()

            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(null, ByteArray(0))) // Empty body
                .headers(allAnimeHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    return "ERROR:${res.code}"
                }
                res.body.string()
            }
        } catch (e: Exception) {
            "EXC:${e.message?.take(30)}"
        }
    }

    fun fetchAllAnimeShowId(title: String): Triple<String, String, String> {
        // --- STRATEGY 1: SearchInput Object ---
        val query1 = "query (\$search: SearchInput!, \$limit: Int, \$page: Int, \$translationType: String, \$countryOrigin: String) { shows(search: \$search, limit: \$limit, page: \$page, translationType: \$translationType, countryOrigin: \$countryOrigin) { edges { _id name } } }"
        val variables1 = """{"search":{"query":"$title","allowAdult":true,"allowUnknown":false},"limit":40,"page":1,"translationType":"sub","countryOrigin":"ALL"}"""
        
        val res1 = makeRequest(query1, variables1)
        if (res1 != null && !res1.startsWith("ERROR") && !res1.startsWith("EXC")) {
            val id = res1.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S1", "")
        }

        // --- STRATEGY 2: Raw String Search ---
        val query2 = "query (\$search: String!) { shows(search: \$search, limit: 40, page: 1, translationType: \"sub\", countryOrigin: \"ALL\") { edges { _id name } } }"
        val variables2 = """{"search":"$title"}"""
        
        val res2 = makeRequest(query2, variables2)
        if (res2 != null && !res2.startsWith("ERROR") && !res2.startsWith("EXC")) {
            val id = res2.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id
            if (!id.isNullOrBlank()) return Triple(id, "S2", "")
        }

        val err = res1 ?: res2 ?: "Null"
        return Triple("", "S0", err.take(50))
    }

    fun fetchAllAnimeEpisodes(showId: String): Triple<Map<String, String>, String, String> {
        // --- STRATEGY 1: Normal ---
        val query1 = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
        val variables1 = """{"showId":"$showId"}"""
        
        val res1 = makeRequest(query1, variables1)
        if (res1 != null && !res1.startsWith("ERROR") && !res1.startsWith("EXC")) {
            val map = res1.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Triple(map, "E1", "")
        }

        // --- STRATEGY 2: Reversed ---
        val query2 = "query (\$showId: String!) { show(_id: \$showId) { _id episodes { episodeString note } } }"
        val variables2 = """{"showId":"$showId"}"""
        
        val res2 = makeRequest(query2, variables2)
        if (res2 != null && !res2.startsWith("ERROR") && !res2.startsWith("EXC")) {
            val map = res2.parseAs<AllAnimeResponse>().data?.show?.episodes?.associate {
                it.episodeString to (it.note?.takeIf { n -> n.isNotBlank() } ?: "Episode ${it.episodeString}")
            }
            if (!map.isNullOrEmpty()) return Triple(map, "E2", "")
        }

        val err = res1 ?: res2 ?: "Null"
        return Triple(emptyMap(), "E0", err.take(50))
    }

    suspend fun fetchVideos(anilistId: Int, showId: String, epNum: Int): List<Video> {
        if (showId.isBlank() || showId == "NA") return emptyList()
        // To be implemented later
        return emptyList()
    }
}
