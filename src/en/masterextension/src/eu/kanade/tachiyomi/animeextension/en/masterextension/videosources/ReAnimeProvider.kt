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

    // ======================== cache ========================

    private data class AnimeInfo(val slug: String, val title: String)
    private val animeCache = ConcurrentHashMap<Int, AnimeInfo>()

    // ======================== VideoProvider ========================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)

        // Step 1: Search
        val info = try {
            findAnime(meta.anilistId, anime.title)
        } catch (e: Exception) {
            return listOf(Video("debug://x", "FAIL search: ${e.message?.take(80)}", "debug://x"))
        }
        if (info == null) {
            return listOf(Video("debug://x", "FAIL: 0 results for '${anime.title}'", "debug://x"))
        }

        // Step 2: Fetch watch page
        val watchUrl = "$baseUrl/watch/${info.slug}?ep=${meta.epNum}&lang=sub&server=HD-2"
        val watchHtml = try {
            client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return listOf(Video("debug://x", "FAIL watch page: ${e.message?.take(80)}", "debug://x"))
        }

        // Step 3: Find flixcloud embed URL
        val embedUrl = FLIXCLOUD_EMBED_REGEX.find(watchHtml)?.value
        if (embedUrl == null) {
            val snippet = watchHtml.take(200).replace("\n", " ")
            return listOf(Video("debug://x", "FAIL: no flixcloud URL in watch page. HTML starts: $snippet", "debug://x"))
        }

        // Step 4: Fetch flixcloud embed page
        val embedHtml = try {
            val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
            client.newCall(GET(embedUrl, embedHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return listOf(Video("debug://x", "FAIL embed fetch: ${e.message?.take(80)}", "debug://x"))
        }

        // Step 5: Parse SvelteKit data
        val dataJson = SVELTEKIT_DATA_REGEX.find(embedHtml)?.groupValues?.get(1)
        if (dataJson == null) {
            val snippet = embedHtml.take(200).replace("\n", " ")
            return listOf(Video("debug://x", "FAIL: no SvelteKit data. HTML starts: $snippet", "debug://x"))
        }

        // Step 6: Extract seed + crypto data
        val pageDataMap = parseFlatData(dataJson)
        val seed = pageDataMap["obfuscation_seed"]
        if (seed == null) {
            return listOf(Video("debug://x", "FAIL: no obfuscation_seed in data", "debug://x"))
        }

        val cryptoDataStr = extractJsonObject(dataJson, "obfuscated_crypto_data")
        if (cryptoDataStr == null) {
            return listOf(Video("debug://x", "FAIL: no obfuscated_crypto_data", "debug://x"))
        }

        // Step 7: Get token ref + call API
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        val tokenRef = pageDataMap[mapping.tokenField]
        if (tokenRef == null) {
            return listOf(Video("debug://x", "FAIL: no tokenRef (field=${mapping.tokenField})", "debug://x"))
        }

        val apiBody = try {
            val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
            val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
            client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess()
                .use { it.body.string() }
        } catch (e: Exception) {
            return listOf(Video("debug://x", "FAIL API call: ${e.message?.take(80)}", "debug://x"))
        }

        // Step 8: Decrypt
        val m3u8Url = try {
            val cryptoData = json.parseToJsonElement(cryptoDataStr).jsonObject
            val apiResponse = json.parseToJsonElement(apiBody).jsonObject
            FlixcloudDecryptor.decrypt(seed, cryptoData, pageDataMap, apiResponse)
        } catch (e: Exception) {
            return listOf(Video("debug://x", "FAIL decrypt: ${e.message?.take(80)}", "debug://x"))
        }

        return listOf(
            Video(
                url = m3u8Url,
                quality = "$name - Auto",
                videoUrl = m3u8Url,
                headers = Headers.Builder()
                    .set("Referer", "$flixcloudBase/")
                    .set("Origin", flixcloudBase)
                    .build(),
            ),
        )
    }

    // ======================== Step 1: Search ========================

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

    // ======================== Step 2: Extract video URL ========================

    private suspend fun extractVideoUrl(slug: String, epNum: Int): String? {
        // Fetch watch page → find flixcloud embed URL
        val watchUrl = "$baseUrl/watch/$slug?ep=$epNum&lang=sub&server=HD-2"
        val watchHtml = client.newCall(GET(watchUrl, reHeaders)).awaitSuccess()
            .use { it.body.string() }

        val embedUrl = FLIXCLOUD_EMBED_REGEX.find(watchHtml)?.value ?: return null

        // Fetch flixcloud embed page
        val embedHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val embedHtml = client.newCall(GET(embedUrl, embedHeaders)).awaitSuccess()
            .use { it.body.string() }

        // Parse SvelteKit data
        val dataJson = SVELTEKIT_DATA_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return null
        val pageDataMap = parseFlatData(dataJson)
        val seed = pageDataMap["obfuscation_seed"] ?: return null
        val cryptoDataStr = extractJsonObject(dataJson, "obfuscated_crypto_data") ?: return null
        val cryptoData = json.parseToJsonElement(cryptoDataStr).jsonObject

        // Get token ref → call flixcloud API
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        val tokenRef = pageDataMap[mapping.tokenField] ?: return null

        val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
        val apiHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .build()
        val apiBody = client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess()
            .use { it.body.string() }
        val apiResponse = json.parseToJsonElement(apiBody).jsonObject

        // Decrypt → m3u8 URL
        return FlixcloudDecryptor.decrypt(seed, cryptoData, pageDataMap, apiResponse)
    }

    // ======================== Helpers ========================

    private fun parseFlatData(dataJson: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex(""""([a-zA-Z0-9_]+)"\s*:\s*"([^"]*?)"""")
        for (match in regex.findAll(dataJson)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (value.isNotEmpty() && !value.startsWith("{") && !value.startsWith("[")) {
                result[key] = value
            }
        }
        return result
    }

    private fun extractJsonObject(dataStr: String, key: String): String? {
        val idx = dataStr.indexOf("\"$key\"")
        if (idx == -1) return null
        val colonIdx = dataStr.indexOf(':', idx + key.length + 2)
        if (colonIdx == -1) return null
        var depth = 0
        var start = -1
        for (i in colonIdx + 1 until dataStr.length) {
            when (dataStr[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start != -1) return dataStr.substring(start, i + 1)
                }
            }
        }
        return null
    }

    companion object {
        private val FLIXCLOUD_EMBED_REGEX =
            Regex("""https://flixcloud\.cc/e/[a-zA-Z0-9]+[^"'\s]*""")

        private val SVELTEKIT_DATA_REGEX =
            Regex("""data:\s*(\[.+?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL)
    }
}
