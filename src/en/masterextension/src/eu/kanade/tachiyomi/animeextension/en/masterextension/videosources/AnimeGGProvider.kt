package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder

class AnimeGGProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AnimeGG"
    override val baseUrl = "https://www.animegg.org"

    companion object {
        private const val BASE = "https://www.animegg.org"
        private val JSON_KEY_FIX = Regex("""(?<=[{,])\s*['"]?(\w+)['"]?\s*:""")
    }

    @Serializable
    private data class GgVideo(val file: String, val label: String)

    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // Helper to normalize titles: lowercase, remove punctuation, collapse spaces
    private fun String.normalize(): String {
        return this.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
    }

    // =================================================================
    // STEP 1: Search → anime URL (with robust scoring)
    // =================================================================
    private suspend fun searchAnime(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/search/?q=$encodedTitle"
        val doc = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()
        
        val elements = doc.select(".mse")
        if (elements.isEmpty()) return null

        val cleanRequested = title.normalize()
        
        val scoredResults = elements.map { el ->
            val elTitle = el.selectFirst(".first h2")?.text()?.trim() ?: ""
            val cleanRes = elTitle.normalize()
            var score = 0

            if (cleanRes == cleanRequested) {
                score += 1000
            } else if (cleanRes.contains(cleanRequested)) {
                score += 800
            } else if (cleanRequested.contains(cleanRes)) {
                score += 600
            }

            val reqWords = cleanRequested.split(" ").filter { it.length > 2 }
            val resWords = cleanRes.split(" ").filter { it.length > 2 }
            val matchingWords = reqWords.count { reqWord ->
                resWords.any { resWord -> resWord.contains(reqWord) || reqWord.contains(resWord) }
            }
            score += matchingWords * 20

            if (matchingWords == 0 && cleanRes != cleanRequested) {
                score = -1000
            }

            Pair(el, score)
        }

        return scoredResults.filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?.attr("abs:href")
    }

    // =================================================================
    // STEP 2: Anime page → episode URL
    // =================================================================
    private suspend fun getEpisodeUrl(animeUrl: String, epNum: Int): String? {
        val doc = client.newCall(GET(animeUrl, headers)).awaitSuccess().asJsoup()
        
        val episodeElement = doc.select(".newmanga li div").firstOrNull { el ->
            val text = el.selectFirst(".anm_det_pop strong")?.text() ?: ""
            val num = getEpNumber(text)
            num != null && num.toInt() == epNum
        }
        
        return episodeElement?.selectFirst(".anm_det_pop")?.attr("abs:href")
    }

    private fun getEpNumber(input: String): Float? {
        val regex = Regex("""(\d+(\.\d+)?)(?:-\d+(\.\d+)?)?$""")
        return regex.find(input)?.groupValues?.get(1)?.toFloatOrNull()
    }

    // =================================================================
    // STEP 3 & 4: Episode page → iframes → extract videoSources (Parallelized)
    // =================================================================
    private suspend fun extractVideos(episodeUrl: String): List<Video> {
        val doc = client.newCall(GET(episodeUrl, headers)).awaitSuccess().asJsoup()
        val iframes = doc.select("iframe")
        
        if (iframes.isEmpty()) return emptyList()
        
        return iframes.parallelCatchingFlatMap { iframe ->
            val mode = when (iframe.closest(".tab-pane")?.attr("id")) {
                "subbed-Animegg" -> "[SUBBED]"
                "dubbed-Animegg" -> "[DUBBED]"
                "raw-Animegg" -> "[RAW]"
                else -> ""
            }
            
            val iframeSrc = iframe.attr("abs:src")
            if (iframeSrc.isBlank()) return@parallelCatchingFlatMap emptyList()
            
            val embedDoc = client.newCall(GET(iframeSrc, headers)).awaitSuccess().asJsoup()
            val host = iframeSrc.toHttpUrlOrNull()?.host ?: ""
            
            val scriptData = embedDoc.selectFirst("script:containsData(var videoSources =)")?.data()
                ?: return@parallelCatchingFlatMap emptyList()
                
            val rawJson = scriptData
                .substringAfter("var videoSources = ")
                .substringBefore(";")
                .replace(JSON_KEY_FIX) { mr -> " \"${mr.groupValues[1]}\":" }
                
            val videos = try {
                rawJson.parseAs<Array<GgVideo>>()
            } catch (e: Exception) {
                return@parallelCatchingFlatMap emptyList()
            }
            
            val videoHeaders = headers.newBuilder()
                .add("Referer", "https://$host/")
                .build()
                
            videos.map { v ->
                val url = if (v.file.startsWith("http")) v.file else "https://$host${v.file}"
                Video(url, "$name $mode ${v.label}", url, headers = videoHeaders)
            }
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        var title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
        
        // Fallback to AniList if title is missing
        if (title.isBlank()) {
            title = fetchTitleFromAniList(meta.anilistId) ?: return debugVideo("Title blank (AL: ${meta.anilistId})")
        }
        
        val epNum = if (meta.epNum > 0) meta.epNum else 1
        
        val animeUrl = searchAnime(title) ?: return debugVideo("search null for '$title'")
        val episodeUrl = getEpisodeUrl(animeUrl, epNum) ?: return debugVideo("no episode $epNum found for '$title'")
        val videos = extractVideos(episodeUrl)
        
        if (videos.isEmpty()) {
            return debugVideo("0 videos extracted from iframes")
        }
        
        return videos
    }

    // ==================== AniList Title Fetcher ====================
    @Serializable private data class AniListMediaResponse(val Media: AniListMediaFull? = null)
    @Serializable private data class AniListMediaFull(val title: AniListTitlesFull? = null)
    @Serializable private data class AniListTitlesFull(val english: String? = null, val romaji: String? = null)

    private suspend fun fetchTitleFromAniList(anilistId: Int): String? {
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject { put("id", anilistId) }
        return try {
            val request = graphQLPost("https://graphql.anilist.co", headers, query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (_: Exception) {
            null
        }
    }
}
