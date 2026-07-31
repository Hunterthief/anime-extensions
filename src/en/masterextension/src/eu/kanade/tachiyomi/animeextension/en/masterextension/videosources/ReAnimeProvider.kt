package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeSearchResponse
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class ReAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "ReAnime"
    override val baseUrl = "https://reanime.to"

    private val json = Json { ignoreUnknownKeys = true }

    private val reHeaders: Headers
        get() = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

    private data class AnimeInfo(val slug: String, val title: String)
    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)

        // Step 1: Search
        val info = try {
            findAnime(meta.anilistId, anime.title)
        } catch (e: Exception) {
            return dbg("FAIL search: ${e.message?.take(80)}")
        }
        if (info == null) return dbg("FAIL: 0 results for '${anime.title}'")

        // Step 2: Try endpoints that might return server/embed info
        val watchRef = "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2"
        val jsonHeaders = headers.newBuilder()
            .set("Referer", watchRef)
            .set("Accept", "application/json")
            .build()

        val endpoints = listOf(
            // SvelteKit __data.json (client-side navigation data)
            "$baseUrl/watch/${info.slug}/__data.json?ep=${meta.epNum}&lang=sub&server=HD-2",
            // Possible API patterns
            "$baseUrl/api/v1/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2",
            "$baseUrl/api/v1/anime/${info.slug}/episode/${meta.epNum}",
            "$baseUrl/api/v1/anime/${info.slug}/servers?ep=${meta.epNum}",
            "$baseUrl/api/v1/anime/${info.slug}/stream?ep=${meta.epNum}&server=HD-2",
        )

        val results = StringBuilder()
        for ((i, ep) in endpoints.withIndex()) {
            try {
                val resp = client.newCall(GET(ep, jsonHeaders)).awaitSuccess()
                    .use { it.body.string() }
                val short = ep.substringAfter(baseUrl).take(40)
                val snippet = resp.take(100).replace("\n", " ")
                results.append("[$i] $short → $snippet | ")
                // If we found something with flixcloud/embed/server, return immediately
                val lower = resp.lowercase()
                if (lower.contains("flixcloud") || lower.contains("embed") || lower.contains("access_id")) {
                    return dbg("HIT[$i] $short: ${resp.take(200).replace("\n", " ")}")
                }
            } catch (e: Exception) {
                val short = ep.substringAfter(baseUrl).take(40)
                results.append("[$i] $short → ERR:${e.message?.take(30)} | ")
            }
        }

        return dbg(results.toString().take(120))
    }

    private suspend fun findAnime(anilistId: Int, title: String): AnimeInfo? {
        animeCache[anilistId]?.let { return it }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/v1/search")
            addQueryParameter("limit", "36")
            addQueryParameter("q", title)
        }.build()

        val response = client.newCall(GET(url, reHeaders)).awaitSuccess()
        val searchResults = response.parseAs<ReAnimeSearchResponse>()
        if (searchResults.results.isEmpty()) return null

        val titleLower = title.lowercase().trim()
        val best = searchResults.results.firstOrNull {
            it.title.english.equals(title, ignoreCase = true) ||
                it.title.romaji.equals(title, ignoreCase = true)
        } ?: searchResults.results.minByOrNull {
            val n = it.title.english.lowercase().trim()
            when {
                n.startsWith(titleLower) -> n.length
                titleLower.startsWith(n) -> n.length + 1000
                n.contains(titleLower) -> n.length + 2000
                else -> Int.MAX_VALUE
            }
        } ?: return null

        val info = AnimeInfo(best.animeId, best.title.english)
        animeCache[anilistId] = info
        return info
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
