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
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

class MasterExtension : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Master Extension"
    override val baseUrl = "https://graphql.anilist.co"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val json = Json { ignoreUnknownKeys = true }

    private val providerManager by lazy { ProviderManager(client, headers, preferences) }

    private fun buildGraphQLRequest(query: String, variables: JsonObject): Request {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(baseUrl, headers, body)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val query = "query (\$page: Int) { Page(page: \$page, perPage: 20) { media(type: ANIME, sort: POPULARITY_DESC) { id idMal title { romaji english } coverImage { large } episodes } } }"
        val variables = buildJsonObject { put("page", JsonPrimitive(page)) }
        return buildGraphQLRequest(query, variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<AniListResponse>(response.body.string()).data?.Page?.media ?: emptyList()
        val animes = data.map { media ->
            SAnime.create().apply {
                url = media.id.toString()
                title = media.title?.romaji ?: media.title?.english ?: "Unknown"
                thumbnail_url = media.coverImage?.large ?: ""
                artist = media.idMal?.toString() ?: ""
                initialized = true
            }
        }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val query = "query (\$page: Int) { Page(page: \$page, perPage: 20) { media(type: ANIME, status: RELEASING, sort: START_DATE_DESC) { id idMal title { romaji english } coverImage { large } episodes } } }"
        val variables = buildJsonObject { put("page", JsonPrimitive(page)) }
        return buildGraphQLRequest(query, variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.find { it is MasterFilters.GenreFilter } as? MasterFilters.GenreFilter
        val formatFilter = filters.find { it is MasterFilters.FormatFilter } as? MasterFilters.FormatFilter
        val sortFilter = filters.find { it is MasterFilters.SortFilter } as? MasterFilters.SortFilter

        val gqlQuery = "query (\$page: Int, \$search: String, \$genre: String, \$format: String, \$sort: [MediaSort]) { Page(page: \$page, perPage: 20) { media(type: ANIME, search: \$search, genre: \$genre, format: \$format, sort: \$sort) { id idMal title { romaji english } coverImage { large } episodes } } }"

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
            put("page", JsonPrimitive(page))
            put("search", JsonPrimitive(query))
            if (genreStr != null) put("genre", JsonPrimitive(genreStr))
            if (formatStr != null) put("format", JsonPrimitive(formatStr))
            put("sort", buildJsonArray { add(JsonPrimitive(sortStr)) })
        }
        return buildGraphQLRequest(gqlQuery, variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        val query = "query (\$id: Int) { Media(id: \$id, type: ANIME) { id idMal title { romaji english native } description episodes status season seasonYear format genres averageScore nextAiringEpisode { episode airingAt } } }"
        val variables = buildJsonObject { put("id", JsonPrimitive(anime.url.toInt())) }
        return buildGraphQLRequest(query, variables)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = json.decodeFromString<AniListResponse>(response.body.string()).data?.Media
        return SAnime.create().apply {
            title = media?.title?.romaji ?: media?.title?.english ?: "Unknown"
            description = media?.description?.let { Jsoup.parse(it).text() }
            status = when (media?.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "NOT_YET_RELEASED" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = media?.genres?.joinToString(", ")
            thumbnail_url = media?.coverImage?.large
            artist = media?.idMal?.toString() ?: ""
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = json.decodeFromString<AniListResponse>(response.body.string()).data?.Media
        val episodeCount = media?.episodes ?: 0
        val episodes = mutableListOf<SEpisode>()

        val maxEpisodes = if (episodeCount == 0) 12 else episodeCount

        for (i in 1..maxEpisodes) {
            episodes.add(
                SEpisode.create().apply {
                    url = "${media?.id}/$i"
                    name = "Episode $i"
                    episode_number = i.toFloat()
                    date_upload = System.currentTimeMillis()
                }
            )
        }
        return episodes.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/")
        val anilistId = parts.firstOrNull()?.toIntOrNull() ?: return emptyList()
        val episodeNumber = parts.lastOrNull()?.toFloat()?.toInt() ?: 1

        return providerManager.fetchVideos(anilistId, episodeNumber)
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
