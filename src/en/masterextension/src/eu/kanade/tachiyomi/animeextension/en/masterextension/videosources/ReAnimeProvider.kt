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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        // Step 3: Parse devalue format and dereference "server"
        return try {
            val root = json.parseToJsonElement(dataBody).jsonObject
            val nodes = root["nodes"]?.jsonArray ?: return dbg("NO NODES array")

            for ((nodeIdx, node) in nodes.withIndex()) {
                if (node is JsonNull) continue
                val nodeObj = try { node.jsonObject } catch (_: Exception) { continue }
                val data = nodeObj["data"]?.jsonArray ?: continue

                // Search for objects containing "server" key
                for (elem in data) {
                    if (elem !is JsonObject) continue
                    val serverRef = elem["server"] ?: continue
                    val serverIdx = serverRef.jsonPrimitive.intOrNull
                    if (serverIdx == null || serverIdx < 0 || serverIdx >= data.size) {
                        return dbg("N$nodeIdx server ref=$serverRef (bad idx)")
                    }

                    // Dereference: get the actual value at that index
                    val serverVal = data[serverIdx]
                    val serverStr = serverVal.toString().take(300)

                    // If it's an object, also dereference its fields
                    if (serverVal is JsonObject) {
                        val resolved = StringBuilder()
                        for ((k, v) in serverVal) {
                            val vIdx = v.jsonPrimitive.intOrNull
                            if (vIdx != null && vIdx >= 0 && vIdx < data.size) {
                                resolved.append("$k=${data[vIdx].toString().take(60)}; ")
                            } else {
                                resolved.append("$k=$v; ")
                            }
                        }
                        return dbg("N$nodeIdx SRV OBJ: $resolved")
                    }

                    return dbg("N$nodeIdx SRV@$serverIdx: $serverStr")
                }
            }

            dbg("NO 'server' KEY in ${nodes.size} nodes")
        } catch (e: Exception) {
            dbg("PARSE ERR: ${e.message?.take(80)}")
        }
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
