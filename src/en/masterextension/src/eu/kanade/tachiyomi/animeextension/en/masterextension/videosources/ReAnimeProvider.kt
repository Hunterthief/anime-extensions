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

        // Step 2: Fetch __data.json
        val dataUrl = "$baseUrl/watch/${info.slug}/__data.json" +
            "?ep=${meta.epNum}&lang=sub&server=HD-2"
        val dataHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2")
            .set("Accept", "application/json")
            .build()

        val dataBody = try {
            client.newCall(GET(dataUrl, dataHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return dbg("FAIL __data.json: ${e.message?.take(80)}")
        }

        // Step 3: Search for "aid" field (flixcloud's internal name for access ID)
        val dataLower = dataBody.lowercase()
        for (kw in listOf("\"aid\"", "\"aid\":", "flixcloud.cc/e/", "flixcloud", "\"url\"", "\"src\"", "\"link\"", "\"href\"")) {
            var searchFrom = 0
            while (true) {
                val idx = dataLower.indexOf(kw, searchFrom)
                if (idx == -1) break
                // Skip embedurl false positive
                if (kw == "\"url\"" || kw == "\"src\"") {
                    val ctx = dataBody.substring(maxOf(0, idx - 5), minOf(dataBody.length, idx + 60))
                    if (ctx.contains("embedurl", ignoreCase = true) || ctx.contains("embed_url", ignoreCase = true)) {
                        searchFrom = idx + kw.length
                        continue
                    }
                }
                val ctx = dataBody
                    .substring(maxOf(0, idx - 20), minOf(dataBody.length, idx + 200))
                    .replace("\n", " ")
                return dbg("$kw@${idx}: $ctx")
            }
        }

        // Step 4: If nothing found in __data.json, fetch the watch page JS module
        val watchHtml = try {
            client.newCall(GET("$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2", reHeaders))
                .awaitSuccess().use { it.body.string() }
        } catch (e: Exception) {
            return dbg("FAIL watch HTML: ${e.message?.take(60)}")
        }

        // Find the watch page module URL (nodes_2.*.js or similar)
        val moduleMatch = Regex("""(/assets/immutable/nodes_\d+\.[A-Za-z0-9_-]+\.js)""")
            .findAll(watchHtml).map { it.groupValues[1] }.distinct().toList()

        if (moduleMatch.isEmpty()) {
            return dbg("NO MODULES found in watch HTML (${watchHtml.length} chars)")
        }

        // Try each module - search for flixcloud URL construction
        for (modulePath in moduleMatch.take(5)) {
            try {
                val moduleBody = client.newCall(GET("$baseUrl$modulePath", reHeaders))
                    .awaitSuccess().use { it.body.string() }
                val mLower = moduleBody.lowercase()
                for (kw in listOf("flixcloud", "embed_url", "iframe", "/e/", "access_id", "aid")) {
                    val idx = mLower.indexOf(kw)
                    if (idx != -1) {
                        val ctx = moduleBody
                            .substring(maxOf(0, idx - 40), minOf(moduleBody.length, idx + 160))
                            .replace("\n", " ")
                        return dbg("MOD ${modulePath.take(25)} '$kw': $ctx")
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        return dbg("NOTHING FOUND. modules=${moduleMatch.size} data=${dataBody.length}ch")
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
