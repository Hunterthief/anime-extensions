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
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AniDBProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniDB"
    override val baseUrl = "https://anidb.app"

    companion object {
        private const val BASE = "https://anidb.app"
        private const val ANILIST_API_URL = "https://graphql.anilist.co"
        private val ANIME_ID_REGEX = Regex("-(\\d+)$")
        private val M3U8_REGEX = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")
        
        // Upgraded to catch "Season 2", "Part 2", and "2nd Season"
        private val SEASON_NUMBER_REGEX = Regex(
            """(?:season|part)\s*(\d+)|(\d+)(?:st|nd|rd|th)\s*(?:season|part)""", 
            RegexOption.IGNORE_CASE
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val animeIdCache = mutableMapOf<String, String>()

    private fun siteHeaders() = headers.newBuilder()
        .set("Referer", "$BASE/")
        .build()

    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // Helper to extract the actual number from the regex match
    private fun extractSeasonNumber(text: String): Int? {
        val match = SEASON_NUMBER_REGEX.find(text) ?: return null
        // Group 1 captures "season 2", Group 2 captures "2nd season"
        val numStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return numStr.toIntOrNull()
    }

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

    @Serializable
    private data class AniListTitleResponse(val Media: AniListMediaTitle? = null)
    @Serializable
    private data class AniListMediaTitle(val title: AniListTitles? = null)
    @Serializable
    private data class AniListTitles(val english: String? = null, val romaji: String? = null)

    private suspend fun getAnimeId(title: String): String? {
        animeIdCache[title]?.let { return it }

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/browse?q=$encodedTitle"
        
        val html = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return null
        }

        val doc = Jsoup.parse(html)
        val links = doc.select("a[href*=/anime/]")
        if (links.isEmpty()) return null
        
        val cleanTitle = title.trim().lowercase()
        val querySeasonNumber = extractSeasonNumber(cleanTitle)
        
        // 1. Try exact match first (highest priority)
        var animeLink: Element? = links.firstOrNull { link ->
            link.text().trim().lowercase() == cleanTitle
        }
        
        // 2. Fallback matching logic
        if (animeLink == null) {
            val candidates = links.filter { link ->
                val text = link.text().trim().lowercase()
                text.contains(cleanTitle) || cleanTitle.contains(text)
            }
            
            animeLink = if (querySeasonNumber != null) {
                // Searching for a specific season/part -> match the exact number
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == querySeasonNumber
                } ?: candidates.minByOrNull { it.text().length }
            } else {
                // No season in query (e.g. "Mob Psycho 100") -> prefer base show (no season number)
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == null
                } ?: candidates.minByOrNull { it.text().length }
            }
        }
        
        // 3. Ultimate fallback
        val finalLink = animeLink ?: links.firstOrNull() ?: return null
        
        val href = finalLink.attr("abs:href")
        val animeId = ANIME_ID_REGEX.find(href)?.groupValues?.get(1) ?: return null
        
        animeIdCache[title] = animeId
        return animeId
    }

    private suspend fun getEpisodeId(animeId: String, epNum: Int): Long? {
        val url = "$BASE/api/frontend/anime/$animeId/episodes"
        val body = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return null
        }

        val episodes = try {
            body.parseAs<EpisodeResponseDto>().episodes
        } catch (e: Exception) {
            return null
        }

        if (episodes.isEmpty()) return null

        return episodes.firstOrNull { ep ->
            ep.number.toFloat() == epNum.toFloat()
        }?.id
    }

    private suspend fun getVideos(episodeId: Long): List<Video> {
        val url = "$BASE/api/frontend/episode/$episodeId/languages"
        val body = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return emptyList()
        }

        val languages = try {
            body.parseAs<LanguageResponseDto>().languages
        } catch (e: Exception) {
            return emptyList()
        }

        return languages.parallelCatchingFlatMap { language ->
            val embedUrl = language.embed_url
            val embedHtml = try {
                client.newCall(GET(embedUrl, siteHeaders())).awaitSuccess().bodyString()
            } catch (e: Exception) {
                return@parallelCatchingFlatMap emptyList<Video>()
            }

            val m3u8Url = M3U8_REGEX.find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMap emptyList<Video>()

            // Determine if this is Sub or Dub based on language name
            val isDub = language.name.equals("English", ignoreCase = true) || 
                       language.name.equals("Dub", ignoreCase = true) ||
                       language.name.contains("Dub", ignoreCase = true)
            
            val audioLabel = if (isDub) "Dub" else "Sub"
            val langPrefix = if (isDub) "English" else "Japanese"

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$BASE/",
                masterHeaders = siteHeaders(),
                videoHeaders = siteHeaders(),
                // Labels videos as "Japanese (Sub) - 720p" or "English (Dub) - 720p"
                // so your Master Extension's Sub/Dub preferences can sort them correctly!
                videoNameGen = { quality -> "$langPrefix ($audioLabel) - $quality" },
            )
        }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        
        val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
        
        if (title.isBlank()) {
            val anilistTitle = fetchTitleFromAniList(meta.anilistId)
            if (anilistTitle.isNullOrBlank()) {
                return debugVideo("title is blank (AniList ID: ${meta.anilistId})")
            }
            return fetchVideosWithTitle(anilistTitle, meta)
        }
        
        return fetchVideosWithTitle(title, meta)
    }

    private suspend fun fetchVideosWithTitle(title: String, meta: EpisodeMeta): List<Video> {
        val epNum = if (meta.epNum > 0) meta.epNum else 1

        val animeId = try {
            getAnimeId(title)
        } catch (e: Exception) {
            return debugVideo("getAnimeId threw: ${e.message}")
        } ?: return debugVideo("getAnimeId null for '$title'")

        val episodeId = try {
            getEpisodeId(animeId, epNum)
        } catch (e: Exception) {
            return debugVideo("getEpisodeId threw: ${e.message}")
        } ?: return debugVideo("getEpisodeId null for ep $epNum (animeId: $animeId)")

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

    private suspend fun fetchTitleFromAniList(anilistId: Int): String? {
        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()

        val variables = buildJsonObject {
            put("id", anilistId)
        }

        return try {
            val request = graphQLPost(ANILIST_API_URL, siteHeaders(), query, variables = variables)
            val response = client.newCall(request).awaitSuccess()
            val data = response.parseGraphQLAs<AniListTitleResponse>()
            data.Media?.title?.english ?: data.Media?.title?.romaji
        } catch (e: Exception) {
            null
        }
    }
}
