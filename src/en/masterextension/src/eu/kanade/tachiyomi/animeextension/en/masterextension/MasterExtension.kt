package eu.kanade.tachiyomi.animeextension.en.masterextension

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.graphQLPost
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Calendar

class MasterExtension : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Master Extension"
    override val baseUrl = "https://graphql.anilist.co"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val providerManager by lazy { ProviderManager(client, headers, preferences) }

    private fun getCurrentSeason(): String {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        return when (month) {
            1, 2, 3 -> "WINTER"
            4, 5, 6 -> "SPRING"
            7, 8, 9 -> "SUMMER"
            10, 11, 12 -> "FALL"
            else -> "WINTER"
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val season = getCurrentSeason()
        val query = "query (\$page: Int, \$season: MediaSeason, \$year: Int) { Page(page: \$page, perPage: 20) { media(type: ANIME, season: \$season, seasonYear: \$year, sort: POPULARITY_DESC) { id title { romaji english } coverImage { large } } } }"
        val variables = buildJsonObject {
            put("page", page)
            put("season", season)
            put("year", year)
        }
        return graphQLPost(baseUrl, headers, query, variables = variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseGraphQLAs<AniListMediaData>().Page?.media ?: emptyList()
        val animes = data.map { media ->
            SAnime.create().apply {
                url = media.id.toString()
                title = media.title?.romaji ?: media.title?.english ?: "Unknown"
                thumbnail_url = media.coverImage?.large ?: ""
                initialized = true
            }
        }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val query = "query (\$page: Int) { Page(page: \$page, perPage: 20) { media(type: ANIME, sort: TRENDING_DESC) { id title { romaji english } coverImage { large } } } }"
        val variables = buildJsonObject { put("page", page) }
        return graphQLPost(baseUrl, headers, query, variables = variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.find { it is MasterFilters.GenreFilter } as? MasterFilters.GenreFilter
        val formatFilter = filters.find { it is MasterFilters.FormatFilter } as? MasterFilters.FormatFilter
        val sortFilter = filters.find { it is MasterFilters.SortFilter } as? MasterFilters.SortFilter

        val gqlQuery = "query (\$page: Int, \$search: String, \$genre: String, \$format: MediaFormat, \$sort: [MediaSort]) { Page(page: \$page, perPage: 20) { media(type: ANIME, search: \$search, genre: \$genre, format: \$format, sort: \$sort) { id title { romaji english } coverImage { large } } } }"

        val genreStr = if (genreFilter?.values?.get(genreFilter.state) == "Any") null else genreFilter?.values?.get(genreFilter.state)
        val formatStr = if (formatFilter?.values?.get(formatFilter.state) == "Any") null else formatFilter?.values?.get(formatFilter.state)
        val sortStr = when (sortFilter?.values?.get(sortFilter.state)) {
            "Popularity" -> "POPULARITY_DESC"
            "Average Score" -> "SCORE_DESC"
            "Newest" -> "START_DATE_DESC"
            "Trending" -> "TRENDING_DESC"
            else -> "SEARCH_MATCH"
        }

        val variables = buildJsonObject {
            put("page", page)
            if (query.isNotBlank()) put("search", query)
            if (genreStr != null) put("genre", genreStr)
            if (formatStr != null) put("format", formatStr)
            put("sort", JsonArray(listOf(JsonPrimitive(sortStr))))
        }
        return graphQLPost(baseUrl, headers, gqlQuery, variables = variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        // FIX: Removed invalid isLicensor field. AniList API only supports isAnimationStudio.
        val query = "query (\$id: Int) { Media(id: \$id, type: ANIME) { id idMal title { romaji english native } description episodes status season seasonYear format genres averageScore studios { nodes { name isAnimationStudio } } nextAiringEpisode { airingAt episode timeUntilAiring } } }"
        val variables = buildJsonObject { put("id", anime.url.toInt()) }
        return graphQLPost(baseUrl, headers, query, variables = variables)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = response.parseGraphQLAs<AniListMediaData>().Media
        return SAnime.create().apply {
            title = media?.title?.romaji ?: media?.title?.english ?: "Unknown"
            
            // Animation Studio is true, Licensors/Producers are false
            val studio = media?.studios?.nodes?.firstOrNull { it.isAnimationStudio == true }?.name ?: "Unknown"
            val producers = media?.studios?.nodes?.filter { it.isAnimationStudio == false }?.joinToString(", ") { it.name ?: "" }?.takeIf { it.isNotBlank() } ?: "Unknown"
            
            val nextEp = media?.nextAiringEpisode
            val nextEpString = if (nextEp != null && nextEp.timeUntilAiring != null) {
                val days = nextEp.timeUntilAiring / 86400
                val hours = (nextEp.timeUntilAiring % 86400) / 3600
                "Episode ${nextEp.episode} airs in ${days}d ${hours}h"
            } else {
                "No upcoming episodes scheduled."
            }

            val desc = media?.description?.let { Jsoup.parse(it).text() } ?: "No synopsis available."
            description = "$desc\n\nStudio: $studio\nProducers: $producers\nStatus: $nextEpString"
            
            status = when (media?.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "NOT_YET_RELEASED" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = media?.genres?.joinToString(", ")
            thumbnail_url = media?.coverImage?.large
            
            author = studio
            artist = producers
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = response.parseGraphQLAs<AniListMediaData>().Media ?: return emptyList()
        val anilistId = media.id
        val title = media.title?.romaji ?: media.title?.english ?: "Unknown"
        
        val nextEp = media.nextAiringEpisode
        val anilistEpCount = media.episodes ?: 0
        val latestAired = if (nextEp != null && nextEp.episode != null) {
            nextEp.episode - 1
        } else if (anilistEpCount > 0) {
            anilistEpCount
        } else {
            12
        }

        val episodes = mutableListOf<SEpisode>()

        // Fetch real episode titles from AllAnime
        var allAnimeEpisodes: Map<String, String> = emptyMap()
        var showId = ""
        try {
            showId = providerManager.fetchAllAnimeShowId(title)
            if (showId.isNotBlank()) {
                allAnimeEpisodes = providerManager.fetchAllAnimeEpisodes(showId)
            }
        } catch (e: Exception) {
            // Ignore, fallback to default names
        }

        for (i in 1..latestAired) {
            val titleStr = allAnimeEpisodes[i.toString()] ?: "Episode $i"
            episodes.add(SEpisode.create().apply {
                // Store AniList ID and AllAnime Show ID and Episode String
                url = "$anilistId/${showId.ifBlank { "NA" }}/$i"
                name = "Ep. $i: $titleStr"
                episode_number = i.toFloat()
                date_upload = System.currentTimeMillis()
            })
        }

        if (nextEp != null && nextEp.episode != null && nextEp.timeUntilAiring != null) {
            val days = nextEp.timeUntilAiring / 86400
            val hours = (nextEp.timeUntilAiring % 86400) / 3600
            episodes.add(SEpisode.create().apply {
                url = "UPCOMING"
                name = "Ep. ${nextEp.episode}: (Upcoming - airs in ${days}d ${hours}h)"
                episode_number = nextEp.episode.toFloat()
                date_upload = System.currentTimeMillis()
            })
        }

        return episodes.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url == "UPCOMING") return emptyList()
        
        val parts = episode.url.split("/")
        val anilistId = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val showId = parts.getOrNull(1) ?: "NA"
        val epNum = parts.getOrNull(2)?.toIntOrNull() ?: 1
        
        return providerManager.fetchVideos(anilistId, showId, epNum)
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_sub_type"
            title = "Preferred Subtitle Type"
            entries = arrayOf("Soft Sub", "Hard Sub", "Dub")
            entryValues = arrayOf("softsub", "hardsub", "dub")
            summary = "%s"
            setDefaultValue("softsub")
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = "consumet_api_url"
            title = "Consumet API URL"
            summary = "Custom or self-hosted URL for Consumet API"
            setDefaultValue("https://api.consumet.org/meta/anilist")
        }.also { screen.addPreference(it) }
    }

    override fun getFilterList(): AnimeFilterList = MasterFilters.filterList
}
