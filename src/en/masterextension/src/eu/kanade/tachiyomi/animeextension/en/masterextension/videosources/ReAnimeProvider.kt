package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.FlixcloudDecryptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeSearchResponse
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    private val flixcloudBase = "https://flixcloud.cc"
    private val json = Json { ignoreUnknownKeys = true }

    private val reHeaders: Headers
        get() = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

    private data class AnimeInfo(val slug: String, val title: String)
    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    // ======================== VideoProvider ========================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)

        // Step 1: Search
        val info = try {
            findAnime(meta.anilistId, anime.title)
        } catch (e: Exception) {
            return dbg("FAIL search: ${e.message?.take(80)}")
        }
        if (info == null) return dbg("FAIL: 0 results for '${anime.title}'")

        // Step 2: Fetch watch page
        val watchUrl = "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2"
        val watchHtml = try {
            client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return dbg("FAIL watch page: ${e.message?.take(80)}")
        }

        // Step 3: Find flixcloud embed URL
        val watchLower = watchHtml.lowercase()
        val flixcloudIdx = watchLower.indexOf("flixcloud")

        if (flixcloudIdx == -1) {
            // flixcloud NOT in HTML — dump SvelteKit data
            val svelteData = SVELTEKIT_DATA_REGEX.find(watchHtml)?.groupValues?.get(1)
            if (svelteData == null) {
                val snippet = watchHtml.take(300).replace("\n", " ")
                return dbg("NO FLIXCLOUD + NO SVELTEKIT. HTML: $snippet")
            }
            val dataSnippet = svelteData.take(400).replace("\n", " ")
            return dbg("NO FLIXCLOUD. SVELTEKIT DATA: $dataSnippet")
        }

        // flixcloud IS mentioned — show context
        val context = watchHtml
            .substring(maxOf(0, flixcloudIdx - 150), minOf(watchHtml.length, flixcloudIdx + 250))
            .replace("\n", " ").replace("\t", " ")
        return dbg("FLIXCLOUD CTX: $context")
    }

    // ======================== Search ========================

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

    // ======================== Helpers ========================

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))

    companion object {
        private val SVELTEKIT_DATA_REGEX =
            Regex("""data:\s*(\[.+?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL)
    }
}
