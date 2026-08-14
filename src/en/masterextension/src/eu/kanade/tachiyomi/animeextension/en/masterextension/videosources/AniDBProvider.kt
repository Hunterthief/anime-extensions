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

    private fun extractSeasonNumber(text: String): Int? {
        val match = SEASON_NUMBER_REGEX.find(text) ?: return null
        val numStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return numStr.toIntOrNull()
    }

    // ==================== DTOs ====================

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
    private data class AniListMediaResponse(val Media: AniListMediaFull? = null)
    @Serializable
    private data class AniListMediaFull(val title: AniListTitlesFull? = null)
    @Serializable
    private data class AniListTitlesFull(
        val english: String? = null,
        val romaji: String? = null,
    )

    // ==================== Step 1: Search by title → extract anime ID ====================

    private suspend fun searchForAnimeId(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/browse?q=$encodedTitle"

        val html = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        val doc = Jsoup.parse(html)
        val links = doc.select("a[href*=/anime/]")
        if (links.isEmpty()) return null

        val cleanTitle = title.trim().lowercase()
        val querySeasonNumber = extractSeasonNumber(cleanTitle)

        // 1. Try exact match first
        var animeLink: Element? = links.firstOrNull { link ->
            link.text().trim().lowercase() == cleanTitle
        }

        // 2. Fallback matching
        if (animeLink == null) {
            val candidates = links.filter { link ->
                val text = link.text().trim().lowercase()
                text.contains(cleanTitle) || cleanTitle.contains(text)
            }

            animeLink = if (querySeasonNumber != null) {
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == querySeasonNumber
                } ?: candidates.minByOrNull { it.text().length }
            } else {
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == null
                } ?: candidates.minByOrNull { it.text().length }
            }
        }

        // 3. BROADER fallback: partial word matching (at least 2 words must match)
        if (animeLink == null) {
            val titleWords = cleanTitle.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
            if (titleWords.size >= 2) {
                animeLink = links.firstOrNull { link ->
                    val linkText = link.text().trim().lowercase()
                    val matchCount = titleWords.count { linkText.contains(it) }
                    matchCount >= 2
                }
            }
        }

        // CRITICAL: If still no match, return null instead of grabbing a random anime
        val finalLink = animeLink ?: return null

        val href = finalLink.attr("abs:href")
        return ANIME_ID_REGEX.find(href)?.groupValues?.get(1)
    }

    private suspend fun getAnimeId(title: String, anilistId: Int): String? {
        val cacheKey = "$anilistId:$title"
        animeIdCache[cacheKey]?.let { return it }

        // Try English title first
        var animeId = searchForAnimeId(title)

        // If English title failed, try romaji from AniList
        if (animeId == null) {
            val romajiTitle = fetchRomajiFromAniList(anilistId)
            if (romajiTitle != null && romajiTitle != title) {
                animeId = searchForAnimeId(romajiTitle)
            }
        }

        if (animeId != null) {
            animeIdCache[cacheKey] = animeId
        }
        return animeId
    }

    // ==================== Step 2: Episodes API → find episode ID ====================

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

        if (episodes.isEmpty()) return null

        return episodes.firstOrNull { ep ->
            ep.number.toFloat() == epNum.toFloat()
        }?.id
    }

    // ==================== Step 3 & 4: Languages API → embed URL → m3u8 ====================

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

            val isDub = language.name.equals("English", ignoreCase = true) ||
                    language.name.contains("Dub", ignoreCase = true)

            val audioLabel = if (isDub) "Dub" else "Sub"
            val langPrefix = if (isDub) "English" else "Japanese"

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$BASE/",
                masterHeaders = siteHeaders(),
                videoHeaders = siteHeaders(),
                videoNameGen = { quality -> "$langPrefix ($audioLabel) - $quality" },
            )
        }
    }

    // ==================== Entry Point ====================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)

        val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title

        if (title.isBlank()) {
            val anilistTitle = fetchRomajiFromAniList(meta.anilistId)
            if (anilistTitle.isNullOrBlank()) {
                return debugVideo("title blank (AL: ${meta.anilistId})")
            }
            return fetchVideosWithTitle(anilistTitle, meta)
        }

        return fetchVideosWithTitle(title, meta)
    }

    private suspend fun fetchVideosWithTitle(title: String, meta: EpisodeMeta): List<Video> {
        val epNum = if (meta.epNum > 0) meta.epNum else 1

        val animeId = try {
            getAnimeId(title, meta.anilistId)
        } catch (e: Exception) {
            return debugVideo("getAnimeId threw: ${e.message}")
        } ?: return debugVideo("getAnimeId null for '$title'")

        val episodeId = try {
            getEpisodeId(animeId, epNum)
        } catch (e: Exception) {
            return debugVideo("getEpisodeId threw: ${e.message}")
        } ?: return debugVideo("getEpisodeId null ep$epNum (id:$animeId)")

        val videos = try {
            getVideos(episodeId)
        } catch (e: Exception) {
            return debugVideo("getVideos threw: ${e.message}")
        }

        if (videos.isEmpty()) {
            return debugVideo("0 videos for ep $epNum (id:$animeId)")
        }

        return videos
    }

    // ==================== AniList Fallback ====================

    private suspend fun fetchRomajiFromAniList(anilistId: Int): String? {
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
            val data = response.parseGraphQLAs<AniListMediaResponse>()
            data.Media?.title?.romaji ?: data.Media?.title?.english
        } catch (_: Exception) {
            null
        }
    }
}
