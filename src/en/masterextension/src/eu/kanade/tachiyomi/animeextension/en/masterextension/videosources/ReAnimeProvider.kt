package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.FlixcloudDecryptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeFlixResponse
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient

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

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val m3u8 = extractVideoUrl(meta.anilistId, meta.epNum) ?: return emptyList()
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

    private suspend fun extractVideoUrl(anilistId: Int, epNum: Int): String? {
        // Step 1: /api/flix/{anilistId}/{epNum} → servers with dataLink
        val flixUrl = "$baseUrl/api/flix/$anilistId/$epNum"
        val flixData = client.newCall(GET(flixUrl, reHeaders)).awaitSuccess()
            .parseAs<ReAnimeFlixResponse>()

        if (!flixData.success || flixData.servers.isEmpty()) return null

        // Step 2: Pick best sub server
        val link = flixData.servers
            .filter { it.dataType.contains("sub", true) }
            .let { subs ->
                subs.find { it.serverName == "HD-2" }
                    ?: subs.find { it.serverName == "HD-1" }
                    ?: subs.firstOrNull()
            }
            ?: flixData.servers.firstOrNull()
            ?: return null

        val embedUrl = link.dataLink
        if (!embedUrl.contains("flixcloud")) return null

        // Step 3: Fetch flixcloud embed page → parse SvelteKit data
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val embedHtml = client.newCall(GET(embedUrl, embedHeaders)).awaitSuccess()
            .use { it.body.string() }

        val dataJson = SVELTEKIT_DATA_REGEX.find(embedHtml)?.groupValues?.get(1)
            ?: return null
        val pageData = parseFlatData(dataJson)
        val seed = pageData["obfuscation_seed"] ?: return null
        val cryptoStr = extractJsonObject(dataJson, "obfuscated_crypto_data")
            ?: return null
        val cryptoData = json.parseToJsonElement(cryptoStr).jsonObject

        // Step 4: Token ref → flixcloud API → encrypted video info
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        val tokenRef = pageData[mapping.tokenField] ?: return null

        val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val apiBody = client.newCall(GET(apiUrl, apiHeaders)).awaitSuccess()
            .use { it.body.string() }
        val apiResponse = json.parseToJsonElement(apiBody).jsonObject

        // Step 5: Decrypt → m3u8 URL
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
        var depth = 0
        var start = -1
        for (i in colon + 1 until dataStr.length) {
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
        private val SVELTEKIT_DATA_REGEX =
            Regex("""data:\s*(\[.+?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL)
    }
}
