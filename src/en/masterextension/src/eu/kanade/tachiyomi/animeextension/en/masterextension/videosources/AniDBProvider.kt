package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * AniDB video source (anidb.app).
 *
 * Clean REST pipeline: Title Search → Episodes API → Languages API → Embed Page Regex → HLS.
 * No encryption, no JS execution, no auth.
 *
 * Flow:
 *   1. GET /browse?q={title} → find anime URL → extract numeric ID via regex
 *   2. GET /api/frontend/anime/{id}/episodes → match epNum → get episode ID
 *   3. GET /api/frontend/episode/{id}/languages → get embed URLs per language
 *   4. GET {embed_url} → regex extract master.m3u8
 *   5. PlaylistUtils.extractFromHls → quality variants
 */
class AniDBProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniDB"
    override val baseUrl = "https://anidb.app"

    companion object {
        private const val BASE = "https://anidb.app"
        private val ANIME_ID_REGEX = Regex("-(\\d+)$")
        private val M3U8_REGEX = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Cache: title → numeric anime ID (avoids re-searching per episode)
    private val animeIdCache = mutableMapOf<String, String>()

    private fun siteHeaders() = headers.newBuilder()
        .set("Referer", "$BASE/")
        .build()

    // =================================================================
    // DEBUG HELPER
    // =================================================================
    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // =================================================================
    // DTOs
    // =================================================================
    @Serializable
    private data class EpisodeResponseDto(val episodes: List<EpisodeDto>)

    @Serializable
    private data class EpisodeDto(
        val id: Long,
        val number: Double,
        val number2: Double? = null,
        val filler: Boolean = false,
    )

    @Serializable
    private data class LanguageResponseDto(val languages: List<LanguageDto>)

    @Serializable
    private data class LanguageDto(
        val name: String,
        val embed_url: String,
    )

    // =================================================================
    // STEP 1: Search by title → extract anime ID
    // =================================================================
    private suspend fun getAnimeId(title: String): String? {
        animeIdCache[title]?.let { return it }

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/browse?q=$encodedTitle"
        
        val html = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        val doc = Jsoup.parse(html)
        val links = doc.select("a[href*=/anime/]")
        val cleanTitle = title.trim().lowercase()
        
        // Try to match title intelligently, fallback to first result
        val animeLink = links.firstOrNull { link ->
            val linkText = link.text().trim().lowercase()
            linkText == cleanTitle || linkText.contains(cleanTitle) || cleanTitle.contains(linkText)
        } ?: links.firstOrNull() ?: return null
        
        val href = animeLink.attr("abs:href")
        val animeId = ANIME_ID_REGEX.find(href)?.groupValues?.get(1) ?: return null
        
        animeIdCache[title] = animeId
        return animeId
    }

    // =================================================================
    // STEP 2: Episodes API → find episode ID
    // =================================================================
    private suspend fun getEpisodeId(animeId: String, epNum: Int): Long? {
        val url = "$BASE/api/frontend/anime/$animeId/episodes"
        val body = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        val episodes = try {
            body.parseAs<EpisodeResponseDto>().episodes
        } catch (_: Exception) {
            return null
        }

        // Match by exact episode number
        return episodes.firstOrNull { ep ->
            ep.number.toFloat() == epNum.toFloat()
        }?.id
    }

    // =================================================================
    // STEP 3 & 4: Languages API → get embed URL → extract m3u8
    // =================================================================
    private suspend fun getVideos(episodeId: Long): List<Video> {
        val url = "$BASE/api/frontend/episode/$episodeId/languages"
        val body = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return emptyList()
        }

        val languages = try {
            body.parseAs<LanguageResponseDto>().languages
        } catch (_: Exception) {
            return emptyList()
        }

        return languages.parallelCatchingFlatMap { language ->
            val embedUrl = language.embed_url
            val embedHtml = try {
                client.newCall(GET(embedUrl, siteHeaders())).awaitSuccess().bodyString()
            } catch (_: Exception) {
                return@parallelCatchingFlatMap emptyList<Video>()
            }

            val m3u8Url = M3U8_REGEX.find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMap emptyList<Video>()

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$BASE/",
                masterHeaders = siteHeaders(),
                videoHeaders = siteHeaders(),
                videoNameGen = { quality -> "${language.name} - $quality" },
            )
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return debugVideo("title is blank")

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        // Step 1: Get Anime ID
        val animeId = try {
            getAnimeId(title)
        } catch (e: Exception) {
            return debugVideo("getAnimeId threw: ${e.message}")
        } ?: return debugVideo("getAnimeId null for '$title'")

        // Step 2: Get Episode ID
        val episodeId = try {
            getEpisodeId(animeId, epNum)
        } catch (e: Exception) {
            return debugVideo("getEpisodeId threw: ${e.message}")
        } ?: return debugVideo("getEpisodeId null for ep $epNum (animeId: $animeId)")

        // Step 3 & 4: Get Videos
        val videos = try {
            getVideos(episodeId)
        } catch (e: Exception) {
            return debugVideo("getVideos threw: ${e.message}")
        }

        if (videos.isEmpty()) {
            return debugVideo("0 videos extracted for ep $epNum")
        }

        return videos
    }
}
