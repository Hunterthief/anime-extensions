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

        // Step 2: Fetch watch page HTML → extract JS module URLs
        val watchUrl = "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2"
        val watchHtml = try {
            client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return dbg("FAIL watch HTML: ${e.message?.take(60)}")
        }

        // Find all JS module URLs (modulepreload + script src)
        val moduleUrls = mutableSetOf<String>()
        for (m in Regex("""(?:src|href)="([^"]*(?:nodes|chunks)[^"]*\.js)"""").findAll(watchHtml)) {
            moduleUrls.add(m.groupValues[1])
        }
        // Also match relative paths like ../assets/immutable/...
        for (m in Regex("""(?:src|href)="(\.\./[^"]*\.js)"""").findAll(watchHtml)) {
            moduleUrls.add(m.groupValues[1])
        }

        if (moduleUrls.isEmpty()) {
            return dbg("NO MODULES in HTML (${watchHtml.length}ch)")
        }

        // Step 3: Fetch each module, search for flixcloud embed construction
        val results = StringBuilder()
        for ((i, modulePath) in moduleUrls.withIndex()) {
            val fullUrl = when {
                modulePath.startsWith("http") -> modulePath
                modulePath.startsWith("../") -> "$baseUrl/${modulePath.removePrefix("../")}"
                modulePath.startsWith("/") -> "$baseUrl$modulePath"
                else -> "$baseUrl/$modulePath"
            }

            try {
                val moduleBody = client.newCall(GET(fullUrl, reHeaders)).awaitSuccess()
                    .use { it.body.string() }

                val mLower = moduleBody.lowercase()
                for (kw in listOf("flixcloud", "embed_url", "iframe_src", "/e/", "access_id", "\"aid\"", "kuudere")) {
                    val idx = mLower.indexOf(kw)
                    if (idx != -1) {
                        val ctx = moduleBody
                            .substring(maxOf(0, idx - 60), minOf(moduleBody.length, idx + 180))
                            .replace("\n", " ").replace("\t", " ")
                        return dbg("MOD[$i] '$kw': $ctx")
                    }
                }
                results.append("[$i]✓ ")
            } catch (e: Exception) {
                results.append("[$i]✗ ")
            }

            // Limit to 15 modules to avoid timeout
            if (i >= 14) break
        }

        return dbg("SCANNED ${moduleUrls.size} modules, no flixcloud. ${results}")
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
