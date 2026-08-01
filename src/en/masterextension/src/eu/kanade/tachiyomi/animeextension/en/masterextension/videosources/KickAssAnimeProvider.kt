package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.KickAssAnimeExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.dto.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.dto.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime.dto.ServersDto
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class KickAssAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {
    override val name = "KickAssAnime"
    override val baseUrl = "https://kaa.lt"
    private val apiUrl = "$baseUrl/api/show"
    
    private val kaaHeaders: Headers
        get() = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

    private val extractor by lazy { KickAssAnimeExtractor(client, kaaHeaders) }
    private val animeCache = ConcurrentHashMap<Int, String>()

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val slug = findAnimeSlug(meta.anilistId, anime.title) ?: return emptyList()
            val epUrl = findEpisodeUrl(slug, meta.epNum) ?: return emptyList()
            
            val videoResp = client.newCall(GET("$apiUrl$epUrl", kaaHeaders)).awaitSuccess()
            val servers = videoResp.parseAs<ServersDto>()
            val hosterExclusion = preferences.getStringSet("kaa_hoster_exclusion", emptySet()) ?: emptySet()
            
            servers.servers.parallelCatchingFlatMap { server ->
                if (hosterExclusion.contains(server.name)) return@parallelCatchingFlatMap emptyList()
                extractor.videosFromUrl(server.src, server.name)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun findAnimeSlug(anilistId: Int, title: String): String? {
        animeCache[anilistId]?.let { return it }
        
        val searchUrl = "$baseUrl/api/fsearch"
        val searchHeaders = kaaHeaders.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Content-Type", "application/json")
            .build()
            
        val body = buildJsonObject {
            put("page", 1)
            put("query", title)
        }.toJsonRequestBody()
        
        val req = POST(searchUrl, headers = searchHeaders, body = body)
        val resp = client.newCall(req).awaitSuccess()
        val results = resp.parseAs<SearchResponseDto>()
        
        val titleLower = title.lowercase().trim()
        val best = results.result.firstOrNull {
            it.title.lowercase().trim() == titleLower || 
            (it.title_en?.lowercase()?.trim() == titleLower)
        } ?: results.result.minByOrNull {
            val n = it.title.lowercase().trim()
            when {
                n.startsWith(titleLower) -> n.length
                titleLower.startsWith(n) -> n.length + 1000
                n.contains(titleLower) -> n.length + 2000
                else -> Int.MAX_VALUE
            }
        }
        
        return best?.slug?.also { animeCache[anilistId] = it }
    }

    private suspend fun findEpisodeUrl(slug: String, epNum: Int): String? {
        val lang = "en-US"
        // Check first 3 pages to find the episode
        for (page in 1..3) {
            val epResp = client.newCall(GET("$apiUrl/$slug/episodes?page=$page&lang=$lang", kaaHeaders)).awaitSuccess()
            val epData = epResp.parseAs<EpisodeResponseDto>()
            
            val ep = epData.result.firstOrNull { 
                it.episode_string.toFloatOrNull()?.toInt() == epNum 
            } ?: epData.result.firstOrNull {
                it.episode_string == epNum.toString()
            }
            
            if (ep != null) {
                return "/$slug/ep-${ep.episode_string}-${ep.slug}"
            }
            if (epData.result.isEmpty()) break
        }
        return null
    }
}
