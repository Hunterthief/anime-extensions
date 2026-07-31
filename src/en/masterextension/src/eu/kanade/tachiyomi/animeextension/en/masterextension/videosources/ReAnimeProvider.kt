package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.FlixcloudDecryptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeSearchResponse
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeWatchResponse
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
        get() = headers.newBuilder().set("Referer", "$baseUrl/").build()

    private data class AnimeInfo(val slug: String, val title: String)
    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val info = findAnime(meta.anilistId, anime.title) ?: return emptyList()
            val m3u8 = extractVideoUrl(info.slug, meta.epNum) ?: return emptyList()
            listOf(
                Video(
                    url = m3u8,
                    quality = "$name - Auto",
                    videoUrl = m3u8,
                    headers = Headers.Builder()
                        .set("Referer", "$flixcloudBase/")
                        .set("Origin", flixcloudBase)
                        .build(),
                ),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ======================== Step 1: Search ========================

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

    // ======================== Step 2: Get flixcloud embed URL ========================

    private suspend fun extractVideoUrl(slug: String, epNum: Int): String? {
        // Call the watch API → returns episode_links with dataLink (flixcloud URL)
        val watchUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/v1/watch/$slug")
            addQueryParameter("ep", epNum.toString())
            addQueryParameter("tz", "UTC")
        }.build()

        val watchResp = client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
        val watchData = watchResp.parseAs<ReAnimeWatchResponse>()

        // Prefer HD-2 sub, fallback to any sub, then any server
        val link = watchData.episodeLinks
            .filter { it.dataType.contains("sub", true) }
            .let { subs ->
                subs.find { it.serverName == "HD-2" }
                    ?: subs.find { it.serverName == "HD-1" }
                    ?: subs.firstOrNull()
            }
            ?: watchData.episodeLinks.firstOrNull()
            ?: return null

        val embedUrl = link.dataLink
        if (!embedUrl.contains("flixcloud")) return null

        // Fetch flixcloud embed page
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val embedHtml = client.newCall(GET(embedUrl, embedHeaders)).awaitSuccess()
            .use { it.body.string() }

        // Parse SvelteKit data
        val dataJson = SVELTEKIT_DATA_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return null
        val pageData = parseFlatData(dataJson)
        val seed = pageData["obfuscation_seed"] ?: return null
        val cryptoStr = extractJsonObject(dataJson, "obfuscated_crypto_data") ?: return null
        val cryptoData = json.parseToJsonElement(cryptoStr).jsonObject

        // Get token ref → call flixcloud API
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        val tokenRef = pageData[mapping.tokenField] ?: return null

        val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val apiBody = client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess()
            .use { it.body.string() }
        val apiResponse = json.parseToJsonElement(apiBody).jsonObject

        // Decrypt → m3u8 URL
        return FlixcloudDecryptor.decrypt(seed, cryptoData, pageData, apiResponse)
    }

    // ======================== Helpers ========================

    private fun parseFlatData(dataJson: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex(""""([a-zA-Z0-9_]+)"\s*:\s*"([^"]*?)"""")
        for (m in regex.findAll(dataJson)) {
            val v = m.groupValues[2]
            if (v.isNotEmpty() && !v.startsWith("{") && !v.startsWith("[")) {
                result[m.groupValues[1]] = v
            }
        }
        return result
    }

    private fun extractJsonObject(dataStr: String, key: String): String? {
        val idx = dataStr.indexOf("\"$key\"")
        if (idx == -1) return null
        val colon = dataStr.indexOf(':', idx + key.length + 2)
        if (colon == -1) return null
        var depth = 0; var start = -1
        for (i in colon + 1 until dataStr.length) {
            when (dataStr[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start != -1) return dataStr.substring(start, i + 1) }
            }
        }
        return null
    }

    companion object {
        private val SVELTEKIT_DATA_REGEX =
            Regex("""data:\s*(\[.+?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL)
    }
}
