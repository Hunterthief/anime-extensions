package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.ANIMEPAHE_UA
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.AnimePaheHlsServer
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.CloudflareInterceptor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.KwikExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.EpisodeDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.ResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe.SearchResultDto
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) : VideoProvider {

    override val name = "AnimePahe"
    
    override val baseUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
            return if (stored != null && stored in PREF_DOMAIN_VALUES) {
                stored
            } else {
                preferences.edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
                PREF_DOMAIN_DEFAULT
            }
        }

    private val cfBypassUserAgent: String
        get() {
            val stored = preferences.getString(PREF_CF_UA_KEY, ANIMEPAHE_UA)
            return if (stored.isNullOrBlank()) ANIMEPAHE_UA else stored.trim()
        }

    private val interceptor = CloudflareInterceptor(client) { cfBypassUserAgent }
    
    private val paheClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    private val extractorClient by lazy {
        paheClient.newBuilder().apply {
            interceptors().removeAll { it is CloudflareInterceptor }
        }.build()
    }

    private val paheHeaders: Headers by lazy {
        headers.newBuilder().set("Referer", "$baseUrl/").build()
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
            
            if (title.isBlank()) return dbg("title blank")
            
            val session = fetchSessionAndId(null, title)?.second 
                ?: return dbg("0 results for '${title.take(30)}' on $baseUrl")

            val episodes = fetchEpisodes(session)
            val epNum = meta.epNum
            
            val matchedEpisode = episodes.firstOrNull { 
                it.episode_number.toInt() == epNum 
            } ?: episodes.getOrNull(epNum - 1) ?: return dbg("ep$epNum not in ${episodes.size}")

            val urlPath = matchedEpisode.url.substringBefore("?")
            val request = GET("$baseUrl$urlPath", paheHeaders)
            val response = paheClient.newCall(request).awaitSuccess()
            val document = response.useAsJsoup()

            val downloadLinks = document.select("div#pickDownload > a")
            val buttons = document.select("div#resolutionMenu > button").withIndex().toList()

            val useHLS = preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)
            val cfUA = cfBypassUserAgent
            val videoList = mutableListOf<Video>()

            buttons.forEach { (index, btn) ->
                val kwikLink = btn.attr("data-src")
                val fullText = btn.text()
                
                var qualityText = if (fullText.contains(" · ")) fullText.substringAfter(" · ") else fullText
                qualityText = qualityText.replace("eng", "", ignoreCase = true)
                    .replace("kor", "", ignoreCase = true)
                    .replace("chi", "", ignoreCase = true)
                    .replace(Regex("\\s+"), " ").trim()
                
                val lang = when {
                    fullText.contains("eng", ignoreCase = true) -> "English"
                    fullText.contains("kor", ignoreCase = true) -> "Korean"
                    fullText.contains("chi", ignoreCase = true) -> "Chinese"
                    else -> "Sub"
                }
                
                val finalQuality = "$qualityText ($lang)"
                val paheWinLink = downloadLinks.getOrNull(index)?.attr("href") ?: ""

                if (!useHLS && paheWinLink.isNotBlank()) {
                    try {
                        val resolvedVideo = KwikExtractor(extractorClient, paheHeaders, cfUA).getStreamVideo(paheWinLink, finalQuality)
                        val proxiedVideo = AnimePaheHlsServer.processMp4VideoList(extractorClient, listOf(resolvedVideo)).firstOrNull()
                        if (proxiedVideo != null) videoList.add(proxiedVideo)
                    } catch (_: Exception) {}
                } else {
                    try {
                        val hlsVideo = KwikExtractor(extractorClient, paheHeaders, cfUA).getHlsVideo(kwikLink, referer = "$baseUrl/", quality = "$finalQuality (HLS)")
                        val proxiedHlsVideos = AnimePaheHlsServer.processVideoList(extractorClient, listOf(hlsVideo))
                        videoList.addAll(proxiedHlsVideos)
                    } catch (_: Exception) {}
                }
            }

            if (videoList.isEmpty()) return dbg("Extractor returned 0 videos")

            val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!.lowercase()
            val shouldBeAv1 = preferences.getBoolean(PREF_AV1_KEY, PREF_AV1_DEFAULT)
            val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
            val preferredLangDisplay = when (preferredLang) {
                "sub" -> "Sub"
                "eng" -> "English"
                "kor" -> "Korean"
                "chi" -> "Chinese"
                else -> "Sub"
            }

            return videoList.sortedWith(
                compareByDescending<Video> { video ->
                    video.quality.lowercase().contains(preferredQuality)
                }.thenByDescending { video ->
                    val title = video.quality.lowercase()
                    title.contains("av1") == shouldBeAv1
                }.thenByDescending { video ->
                    val title = video.quality.lowercase()
                    QUALITY_REGEX_P.find(title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: QUALITY_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                }.thenByDescending { video ->
                    video.quality.contains("($preferredLangDisplay)", ignoreCase = true)
                }
            )
        } catch (e: Throwable) {
            dbg("FATAL: ${e::class.simpleName}: ${e.message?.take(60)}")
        }
    }

    private suspend fun fetchEpisodes(session: String): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        var page = 1
        
        while (true) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("m", "release")
                addQueryParameter("id", session)
                addQueryParameter("sort", "episode_asc")
                addQueryParameter("page", page.toString())
            }.build()

            val response = safeApiCall(GET(url, paheHeaders))
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code} fetching episodes")
            }
            
            val currentData = response.use { it.parseAs<ResponseDto<EpisodeDto>>() }
            if (currentData.items.isNotEmpty()) {
                episodeList.addAll(parseEpisodePage(currentData.items, session))
            }
            
            if (currentData.currentPage >= currentData.lastPage) break
            page++
            delay(1000.milliseconds)
        }
        
        val showSiteEpisodeNumber = preferences.getBoolean(PREF_SHOW_SITE_NUMBER_KEY, PREF_SHOW_SITE_NUMBER_DEFAULT)

        return episodeList.mapIndexed { index, episode ->
            val siteEpisodeNumber = episode.name.removePrefix("Episode ")
            episode.apply {
                episode_number = (index + 1).toFloat()
                name = if (showSiteEpisodeNumber && siteEpisodeNumber != (index + 1).toString()) {
                    "Episode ${index + 1} ($siteEpisodeNumber)"
                } else {
                    "Episode ${index + 1}"
                }
            }
        }.reversed()
    }

    private fun parseEpisodePage(episodes: List<EpisodeDto>, animeSession: String): List<SEpisode> = episodes.map { episode ->
        SEpisode.create().apply {
            val session = episode.session
            url = "/play/$animeSession/$session?anime_id=${episode.animeId}"
            val epNum = episode.episodeNumber
            episode_number = epNum
            val epName = if (floor(epNum) == ceil(epNum)) {
                epNum.toInt().toString()
            } else {
                epNum.toString()
            }
            name = "Episode $epName"
        }
    }

    private suspend fun fetchSessionAndId(animeId: String?, title: String?): Pair<String, String>? {
        if (title.isNullOrBlank()) return null
        val searchQuery = normalizeSearchQuery(title)
        val words = searchQuery.split(" ").filter { it.isNotBlank() }
        val normalizedTitle = normalizeTitle(title)

        var result = searchApiForId(animeId, normalizedTitle, searchQuery)
        if (result != null) return result

        val trailingLengths = listOf(4, 3)
        for (len in trailingLengths) {
            if (words.size > len) {
                val shortQuery = words.takeLast(len).joinToString(" ")
                result = searchApiForId(animeId, normalizedTitle, shortQuery)
                if (result != null) return result
            }
        }
        return null
    }

    private suspend fun searchApiForId(animeId: String?, normalizedTitle: String?, originalQuery: String): Pair<String, String>? {
        var page = 1
        var hasNextPage = true

        while (hasNextPage && page <= 10) {
            val timeSuffix = (System.currentTimeMillis() / 1000) + (page * 3)
            val fullQuery = "$originalQuery $timeSuffix"

            val searchUrl = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addQueryParameter("m", "search")
                addQueryParameter("q", fullQuery)
                addQueryParameter("page", page.toString())
            }.build()

            val result = try {
                val response = safeApiCall(GET(searchUrl, paheHeaders))
                response.use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val searchData = resp.parseAs<ResponseDto<SearchResultDto>>()
                    hasNextPage = searchData.currentPage < searchData.lastPage

                    val matchedAnime = if (animeId != null) {
                        searchData.items.firstOrNull { it.id.toString() == animeId }
                    } else if (normalizedTitle != null) {
                        searchData.items.firstOrNull {
                            val apiTitle = normalizeTitle(it.title)
                            apiTitle.contains(normalizedTitle) || normalizedTitle.contains(apiTitle)
                        }
                    } else {
                        null
                    }
                    matchedAnime?.let { it.id.toString() to it.session }
                }
            } catch (_: Exception) { null }

            if (result != null) return result
            if (hasNextPage && page < 10) delay(1000.milliseconds)
            page++
        }
        return null
    }

    private suspend fun safeApiCall(request: okhttp3.Request): Response {
        var response = paheClient.newCall(request).await()
        if (response.code == 429) {
            response.close()
            delay(12000.milliseconds)
            response = paheClient.newCall(request).await()
        }
        return response
    }

    private fun normalizeSearchQuery(raw: String): String = raw
        .replace(Regex("[^a-zA-Z0-9\\s]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTitle(raw: String): String = raw
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

    private fun dbg(msg: String): List<Video> = listOf(Video("debug://x", msg.take(120), "debug://x"))

    companion object {
        private val QUALITY_REGEX_P by lazy { Regex("""(\d+)p""") }
        private val QUALITY_REGEX by lazy { Regex("""(\d+)""") }

        private const val PREF_DOMAIN_KEY = "animepahe_preferred_domain"
        private val PREF_DOMAIN_VALUES = arrayOf("https://animepahe.pw", "https://animepahe.com", "https://animepahe.org")
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.pw"
        
        private const val PREF_QUALITY_KEY = "animepahe_preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        
        private const val PREF_LANG_KEY = "animepahe_preferred_lang"
        private const val PREF_LANG_DEFAULT = "sub"
        
        private const val PREF_LINK_TYPE_KEY = "animepahe_preferred_link_type"
        private const val PREF_LINK_TYPE_DEFAULT = true
        
        private const val PREF_AV1_KEY = "animepahe_preferred_av1"
        private const val PREF_AV1_DEFAULT = false
        
        private const val PREF_SHOW_SITE_NUMBER_KEY = "animepahe_show_site_number"
        private const val PREF_SHOW_SITE_NUMBER_DEFAULT = false
        
        private const val PREF_CF_UA_KEY = "animepahe_cf_bypass_ua"
    }
}
