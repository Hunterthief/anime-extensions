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
        private val ANIME_ID_REGEX = Regex("-(\\d+)$")
        private val M3U8_REGEX = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")
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
        
        // 1. Try exact match first (highest priority)
        var animeLink: Element? = links.firstOrNull { link ->
            link.text().trim().lowercase() == cleanTitle
        }
        
        // 2. If no exact match, look for titles that contain the search query
        // But prefer shorter titles (base show over "Season X Part Y")
        if (animeLink == null) {
            val candidates = links.filter { link ->
                val text = link.text().trim().lowercase()
                text.contains(cleanTitle) || cleanTitle.contains(text)
            }
            
            // If searching for "Season 2", prefer titles with "season 2"
            val hasSeasonInQuery = cleanTitle.contains("season")
            animeLink = if (hasSeasonInQuery) {
                candidates.firstOrNull { it.text().lowercase().contains("season") }
                    ?: candidates.minByOrNull { it.text().length }
            } else {
                // Otherwise prefer shorter titles (base show)
                candidates.minByOrNull { it.text().length }
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

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$BASE/",
                masterHeaders = siteHeaders(),
                videoHeaders = siteHeaders(),
                videoNameGen = { quality -> "${language.name} - $quality" },
            )
        }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        
        // Use anime.title if available, otherwise fall back to meta.title
        val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
        
        // If title is still blank, try to fetch it from AniList using the ID
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

    // Fetch title from AniList API as fallback
    private suspend fun fetchTitleFromAniList(anilistId: Int): String? {
        val query = """
            query {
                Media(id: $anilistId, type: ANIME) {
                    title { english romaji }
                }
            }
        """.trimIndent()

        val body = """{"query":"$query"}"""
        
        return try {
            val request = eu.kanade.tachiyomi.network.POST(
                "https://graphql.anilist.co",
                headers = Headers.Builder()
                    .set("Content-Type", "application/json")
                    .build(),
                body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"),
                    body
                )
            )
            
            val response = client.newCall(request).awaitSuccess().bodyString()
            val json = kotlinx.serialization.json.Json.parseToJsonElement(response)
                as kotlinx.serialization.json.JsonObject
            
            val media = json["data"]?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("Media") as? kotlinx.serialization.json.JsonObject
            
            val title = media?.get("title") as? kotlinx.serialization.json.JsonObject
            val english = title?.get("english")?.let { 
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull 
            }
            val romaji = title?.get("romaji")?.let { 
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull 
            }
            
            english ?: romaji
        } catch (e: Exception) {
            null
        }
    }
}
