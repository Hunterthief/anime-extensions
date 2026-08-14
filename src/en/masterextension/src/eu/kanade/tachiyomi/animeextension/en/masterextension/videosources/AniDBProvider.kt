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

    // Remove season/part info from a title to get the base title
    private fun stripSeasonInfo(title: String): String {
        return title
            .replace(Regex("""\s*[-:]\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+(?:st|nd|rd|th)\s*(?:season|part).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
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

    // ==================== Search anidb.app and return all anime links ====================

    private suspend fun searchAniDB(query: String): List<Element> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/browse?q=$encodedQuery"

        val html = try {
            client.newCall(GET(url, siteHeaders())).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html)
        return doc.select("a[href*=/anime/]")
    }

    // ==================== Step 1: Find anime ID with smart matching ====================

    private suspend fun findAnimeId(title: String, anilistId: Int): String? {
        val cacheKey = "$anilistId:$title"
        animeIdCache[cacheKey]?.let { return it }

        val cleanTitle = title.trim().lowercase()
        val querySeasonNumber = extractSeasonNumber(cleanTitle)
        val baseTitle = stripSeasonInfo(title)

        // Strategy 1: Search with full title, try exact match
        val fullResults = searchAniDB(title)
        var animeLink: Element? = fullResults.firstOrNull { link ->
            link.text().trim().lowercase() == cleanTitle
        }

        // Strategy 2: If no exact match and we have a season number,
        // search with BASE title to get ALL entries, then filter for correct season
        if (animeLink == null && querySeasonNumber != null && baseTitle != title) {
            val baseResults = searchAniDB(baseTitle)

            // Look for the specific season in the base results
            animeLink = baseResults.firstOrNull { link ->
                val linkSeason = extractSeasonNumber(link.text().lowercase())
                linkSeason == querySeasonNumber
            }

            // Also check full results for season match
            if (animeLink == null) {
                animeLink = fullResults.firstOrNull { link ->
                    val linkSeason = extractSeasonNumber(link.text().lowercase())
                    linkSeason == querySeasonNumber
                }
            }
        }

        // Strategy 3: Contains matching with season awareness
        if (animeLink == null) {
            val allResults = if (baseTitle != title) {
                (fullResults + searchAniDB(baseTitle)).distinctBy { it.attr("href") }
            } else {
                fullResults
            }

            val candidates = allResults.filter { link ->
                val text = link.text().trim().lowercase()
                text.contains(cleanTitle) || cleanTitle.contains(text)
            }

            animeLink = if (querySeasonNumber != null) {
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == querySeasonNumber
                } ?: candidates.firstOrNull { link ->
                    val linkText = link.text().lowercase()
                    linkText.contains("season") || linkText.contains("part")
                }
            } else {
                candidates.firstOrNull { link ->
                    extractSeasonNumber(link.text().lowercase()) == null
                } ?: candidates.minByOrNull { it.text().length }
            }
        }

        // Strategy 4: Broader word matching with season awareness
        if (animeLink == null) {
            val titleWords = cleanTitle.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
            if (titleWords.size >= 2) {
                val allResults = if (baseTitle != title) {
                    (fullResults + searchAniDB(baseTitle)).distinctBy { it.attr("href") }
                } else {
                    fullResults
                }

                val matched = allResults.filter { link ->
                    val linkText = link.text().trim().lowercase()
                    val matchCount = titleWords.count { linkText.contains(it) }
                    matchCount >= 2
                }

                // If we have a season number, prefer results with matching season
                animeLink = if (querySeasonNumber != null) {
                    matched.firstOrNull { link ->
                        extractSeasonNumber(link.text().lowercase()) == querySeasonNumber
                    } ?: matched.firstOrNull()
                } else {
                    matched.firstOrNull { link ->
                        extractSeasonNumber(link.text().lowercase()) == null
                    } ?: matched.firstOrNull()
                }
            }
        }

        // Strategy 5: Try romaji from AniList
        if (animeLink == null) {
            val romajiTitle = fetchTitleFromAniList(anilistId, preferRomaji = true)
            if (romajiTitle != null && romajiTitle.lowercase() != cleanTitle) {
                val romajiResults = searchAniDB(romajiTitle)
                val romajiSeason = extractSeasonNumber(romajiTitle.lowercase())

                animeLink = if (romajiSeason != null) {
                    romajiResults.firstOrNull { link ->
                        extractSeasonNumber(link.text().lowercase()) == romajiSeason
                    } ?: romajiResults.firstOrNull()
                } else {
                    romajiResults.firstOrNull { link ->
                        extractSeasonNumber(link.text().lowercase()) == null
                    } ?: romajiResults.firstOrNull()
                }
            }
        }

        val finalLink = animeLink ?: return null
        val href = finalLink.attr("abs:href")
        val animeId = ANIME_ID_REGEX.find(href)?.groupValues?.get(1) ?: return null

        animeIdCache[cacheKey] = animeId
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

        // Try exact match first
        val exactMatch = episodes.firstOrNull { ep ->
            ep.number.toFloat() == epNum.toFloat()
        }
        if (exactMatch != null) return exactMatch.id

        // Fallback: try number2 field (some entries use this for alternate numbering)
        val number2Match = episodes.firstOrNull { ep ->
            ep.number2?.toFloat() == epNum.toFloat()
        }
        if (number2Match != null) return number2Match.id

        // Last resort: positional match (epNum-th episode in the list)
        if (epNum > 0 && epNum <= episodes.size) {
            return episodes[epNum - 1].id
        }

        return null
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
            val anilistTitle = fetchTitleFromAniList(meta.anilistId, preferRomaji = false)
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
            findAnimeId(title, meta.anilistId)
        } catch (e: Exception) {
            return debugVideo("findAnimeId threw: ${e.message}")
        } ?: return debugVideo("findAnimeId null for '$title'")

        val episodeId = try {
            getEpisodeId(animeId, epNum)
        } catch (e: Exception) {
            return debugVideo("getEpisodeId threw: ${e.message}")
        } ?: return debugVideo("getEpId null ep$epNum id:$animeId")

        val videos = try {
            getVideos(episodeId)
        } catch (e: Exception) {
            return debugVideo("getVideos threw: ${e.message}")
        }

        if (videos.isEmpty()) {
            return debugVideo("0 videos ep$epNum id:$animeId")
        }

        return videos
    }

    // ==================== AniList Title Fetcher ====================

    private suspend fun fetchTitleFromAniList(anilistId: Int, preferRomaji: Boolean): String? {
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
            if (preferRomaji) {
                data.Media?.title?.romaji ?: data.Media?.title?.english
            } else {
                data.Media?.title?.english ?: data.Media?.title?.romaji
            }
        } catch (_: Exception) {
            null
        }
    }
}
