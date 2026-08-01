package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.FlixcloudDecryptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime.ReAnimeFlixResponse
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
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
        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(50)}")
        }
        
        val m3u8 = try {
            extractVideoUrl(meta.anilistId, meta.epNum)
        } catch (e: Exception) {
            return dbg("EXT ERR: ${e.message?.take(60)}")
        }
        
        if (m3u8 == null) return dbg("NULL m3u8 al=${meta.anilistId} ep=${meta.epNum}")
        
        return listOf(
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
    }

    private suspend fun extractVideoUrl(anilistId: Int, epNum: Int): String? {
        // Step 1: /api/flix/{anilistId}/{epNum}
        val flixUrl = "$baseUrl/api/flix/$anilistId/$epNum"
        val flixResp = client.newCall(GET(flixUrl, reHeaders)).await()
        if (!flixResp.isSuccessful) throw Exception("flix API returned ${flixResp.code}")
        
        val flixBody = flixResp.body.string()
        val flixData = json.decodeFromString<ReAnimeFlixResponse>(flixBody)
        
        if (!flixData.success || flixData.servers.isEmpty()) {
            throw Exception("flix: success=${flixData.success} servers=${flixData.servers.size}")
        }
        
        // Step 2: Pick server (prefer HD-2 sub, fallback to HD-1 sub, then any)
        val link = flixData.servers
            .filter { it.dataType.contains("sub", true) }
            .let { subs ->
                subs.find { it.serverName == "HD-2" }
                ?: subs.find { it.serverName == "HD-1" }
                ?: subs.firstOrNull()
            }
            ?: flixData.servers.firstOrNull()
            ?: throw Exception("no server found")
            
        val embedUrl = link.dataLink
        if (!embedUrl.contains("flixcloud")) throw Exception("not flixcloud: ${embedUrl.take(40)}")
        
        // Step 3: Fetch embed page
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val embedResp = client.newCall(GET(embedUrl, embedHeaders)).await()
        if (!embedResp.isSuccessful) throw Exception("embed page ${embedResp.code}")
        val embedHtml = embedResp.body.string()
        
        // Step 4: Parse SvelteKit data
        val dataJson = SVELTEKIT_DATA_REGEX.find(embedHtml)?.groupValues?.get(1)
            ?: throw Exception("no SvelteKit data in embed (${embedHtml.length}ch)")
            
        val pageData = parseFlatData(dataJson)
        val seed = pageData["obfuscation_seed"]
            ?: throw Exception("no obfuscation_seed")
            
        val cryptoStr = extractJsonObject(dataJson, "obfuscated_crypto_data")
            ?: throw Exception("no obfuscated_crypto_data")
            
        val cryptoData = json.parseToJsonElement(cryptoStr).jsonObject
        
        // Step 5: Token ref -> flixcloud API
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        val tokenRef = pageData[mapping.tokenField]
            ?: throw Exception("no tokenRef (field=${mapping.tokenField})")
            
        val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val apiResp = client.newCall(GET(apiUrl, apiHeaders)).await()
        if (!apiResp.isSuccessful) throw Exception("flixcloud API ${apiResp.code}")
        
        val apiBody = apiResp.body.string()
        val apiResponse = json.parseToJsonElement(apiBody).jsonObject
        
        // Step 6: Decrypt
        return FlixcloudDecryptor.decrypt(seed, cryptoData, pageData, apiResponse)
    }

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

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))

    companion object {
        private val SVELTEKIT_DATA_REGEX =
            Regex("""data:\s*(\[.+?\]),\s*form:""", RegexOption.DOT_MATCHES_ALL)
    }
}
