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
        get() = headers.newBuilder().set("Referer", "$baseUrl/").build()

    private data class AnimeInfo(val slug: String, val title: String)
    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(50)}")
        }

        val info = try {
            findAnime(meta.anilistId, anime.title)
        } catch (e: Exception) {
            return dbg("SEARCH ERR: ${e.message?.take(50)}")
        }
        if (info == null) return dbg("0 results for '${anime.title.take(30)}'")

        // Fetch watch page HTML
        val watchUrl = "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2"
        val watchHtml = try {
            client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return dbg("HTML ERR: ${e.message?.take(50)}")
        }

        // Find .js references — show context to understand format
        val jsIdx = watchHtml.indexOf(".js")
        if (jsIdx == -1) return dbg("NO .js in ${watchHtml.length}ch HTML")

        // Extract ALL quoted strings containing .js
        val allJsUrls = Regex("""["']([^"']*\.js[^"']*)["']""")
            .findAll(watchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Filter for SvelteKit modules
        val moduleUrls = allJsUrls.filter {
            it.contains("immutable") || it.contains("entry") ||
                it.contains("nodes") || it.contains("chunks")
        }

        if (moduleUrls.isEmpty()) {
            // Show context around first .js to understand format
            val ctx = watchHtml
                .substring(maxOf(0, jsIdx - 60), minOf(watchHtml.length, jsIdx + 30))
                .replace("\n", " ")
            return dbg("NO MODS (${allJsUrls.size} js). CTX: $ctx")
        }

        // Fetch modules, search for flixcloud embed construction
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
                for (kw in listOf("flixcloud", "embed_url", "iframe", "/e/", "access_id", "\"aid\"", "kuudere")) {
                    val idx = mLower.indexOf(kw)
                    if (idx != -1) {
                        val ctx = moduleBody
                            .substring(maxOf(0, idx - 40), minOf(moduleBody.length, idx + 80))
                            .replace("\n", " ")
                        return dbg("M[$i]'$kw': $ctx")
                    }
                }
            } catch (_: Exception) {
                continue
            }

            if (i >= 14) break
        }

        return dbg("SCANNED ${moduleUrls.size} mods, no flixcloud")
    }

    private suspend fun findAnime(anilistId: Int, title: String): AnimeInfo? {
        animeCache[anilistId]?.let { return it }
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/v1/search")
            addQueryParameter("limit", "36")
            addQueryParameter("q", title)
        }.build()
        val resp = client.newCall(GET(url, reHeaders)).awaitSuccess()
        val results = resp.parseAs<ReAnimeSearchResponse>()
        if (results.results.isEmpty()) return null

        val tl = title.lowercase().trim()
        val best = results.results.firstOrNull {
            it.title.english.equals(title, true) || it.title.romaji.equals(title, true)
        } ?: results.results.minByOrNull {
            val n = it.title.english.lowercase().trim()
            when {
                n.startsWith(tl) -> n.length
                tl.startsWith(n) -> n.length + 1000
                n.contains(tl) -> n.length + 2000
                else -> Int.MAX_VALUE
            }
        } ?: return null

        return AnimeInfo(best.animeId, best.title.english).also { animeCache[anilistId] = it }
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
