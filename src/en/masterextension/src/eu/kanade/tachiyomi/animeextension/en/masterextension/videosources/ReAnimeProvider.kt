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
        
        // Step 2: Pick server
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
        
        // Step 4: Extract seed directly from HTML (handles unquoted JS keys)
        val seed = extractValueByKey(embedHtml, "obfuscation_seed")
            ?: throw Exception("no obfuscation_seed in embed HTML")
            
        // Step 5: Compute field mappings from seed
        val mapping = FlixcloudDecryptor.resolveFieldMapping(seed)
        
        // Step 6: Extract dynamic fields directly from HTML
        val tokenRef = extractValueByKey(embedHtml, mapping.tokenField)
            ?: throw Exception("no tokenRef (field=${mapping.tokenField})")
            
        val keyFrag2 = extractValueByKey(embedHtml, mapping.keyFrag2Field)
            ?: throw Exception("no keyFrag2 (field=${mapping.keyFrag2Field})")
            
        // Step 7: Extract obfuscated_crypto_data JSON object using brace counting
        val cryptoStr = extractJsonObject(embedHtml, "obfuscated_crypto_data")
            ?: throw Exception("no obfuscated_crypto_data in HTML")
        val cryptoData = json.parseToJsonElement(cryptoStr).jsonObject
        
        // Step 8: Token ref -> flixcloud API
        val apiUrl = "$flixcloudBase/api/m3u8/$tokenRef"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val apiResp = client.newCall(GET(apiUrl, apiHeaders)).await()
        if (!apiResp.isSuccessful) throw Exception("flixcloud API ${apiResp.code}")
        
        val apiBody = apiResp.body.string()
        val apiResponse = json.parseToJsonElement(apiBody).jsonObject
        
        // Step 9: Decrypt
        val pageData = mapOf(
            mapping.tokenField to tokenRef,
            mapping.keyFrag2Field to keyFrag2
        )
        
        return FlixcloudDecryptor.decrypt(seed, cryptoData, pageData, apiResponse)
    }

    /**
     * Extracts a string value by key from raw HTML/JS.
     * Handles both quoted ("key":"value") and unquoted (key:"value") JS object literals.
     */
    private fun extractValueByKey(html: String, key: String): String? {
        val regex = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*"([^"]*?)"""")
        return regex.find(html)?.groupValues?.get(1)
    }

    /**
     * Extracts a nested JSON object string by key using brace-depth counting.
     * Much more robust than regex for nested objects.
     */
    private fun extractJsonObject(html: String, key: String): String? {
        val keyRegex = Regex("""["']?${Regex.escape(key)}["']?\s*:\s*\{""")
        val match = keyRegex.find(html) ?: return null
        val start = match.range.last // Index of the first '{'
        var depth = 0
        for (i in start until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(115), "debug://x"))
}
