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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.util.Calendar
import kotlin.math.roundToInt

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
                title = media.title?.english ?: media.title?.romaji ?: "Unknown"
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
        val query = "query (\$id: Int) { Media(id: \$id, type: ANIME) { id idMal title { romaji english native } description episodes duration rating status season seasonYear format genres averageScore studios { nodes { name isAnimationStudio } } nextAiringEpisode { airingAt episode timeUntilAiring } } }"
        val variables = buildJsonObject { put("id", anime.url.toInt()) }
        return graphQLPost(baseUrl, headers, query, variables = variables)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = response.parseGraphQLAs<AniListMediaData>().Media
        return SAnime.create().apply {
            title = media?.title?.english ?: media?.title?.romaji ?: "Unknown"
            
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

            val synopsis = media?.description?.let { cleanSynopsis(it) }?.takeIf { it.isNotBlank() } ?: "No synopsis available."
            val scoreStr = media?.averageScore?.let { "$it%" }
            val starLine = starRatingLine(scoreStr)
            val statusValue = nextEpString.trim()
            
            val type = media?.format?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercaseChar() } ?: ""
            val seasonStr = (media?.season?.lowercase()?.replaceFirstChar { it.uppercaseChar() } ?: "") + " " + (media?.seasonYear?.toString() ?: "")
            val infoLine = buildInfoLine(type, seasonStr.trim(), episodesText(media?.episodes), durationText(media?.duration))
            val genreValue = media?.genres?.toDisplayList()
            
            val ratingMap = mapOf(
                "G" to "G - All Ages",
                "PG" to "PG - Children",
                "PG_13" to "PG-13 - Teens 13 or older",
                "R" to "R - 17+ (violence & profanity)",
                "R_PLUS" to "R+ - Mild Nudity",
                "RX" to "Rx - Hentai"
            )
            val ratingValue = media?.rating?.let { ratingMap[it] ?: it } ?: "N/A"
            val ratingLine = "**Rating:** $ratingValue"

            description = buildDescription(
                starLine,
                synopsis,
                "**Status:** $statusValue",
                infoLine,
                genreValue?.takeIf { it.isNotBlank() }?.let { "**Genres:** $it" },
                ratingLine
            )
            
            status = when (media?.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "NOT_YET_RELEASED" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = genreValue
            thumbnail_url = media?.coverImage?.large
            
            author = studio
            artist = producers
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = response.parseGraphQLAs<AniListMediaData>().Media ?: return emptyList()
        val anilistId = media.id
        val malId = media.idMal
        
        val englishTitle = media.title?.english
        val romajiTitle = media.title?.romaji
        
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

        var malEpisodes: List<MalEpisode> = emptyList()
        var showId = ""

        try {
            val titleToSearch = englishTitle ?: romajiTitle ?: ""
            
            if (titleToSearch.isNotBlank()) {
                val (id, _, _) = providerManager.fetchAllAnimeShowId(titleToSearch)
                showId = id
            }
            
            if (malId != null) {
                val (mList, _, _) = providerManager.fetchMalEpisodes(malId)
                malEpisodes = mList
            }
        } catch (e: Exception) {
            // Ignore
        }

        val malEpMap = malEpisodes.associateBy { it.number }

        for (i in 1..latestAired) {
            val malEp = malEpMap[i.toString()] ?: malEpMap[String.format("%02d", i)]
            val titleStr = malEp?.title ?: "Episode $i"
            
            episodes.add(SEpisode.create().apply {
                url = "$anilistId/${showId.ifBlank { "NA" }}/$i"
                name = "Ep. $i: $titleStr"
                episode_number = i.toFloat()
                date_upload = malEp?.date ?: 0L
                scanlator = "" // Explicitly empty so no text shows next to the date
            })
        }

        return episodes.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
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

    // --- DESCRIPTION HELPER FUNCTIONS ---

    private fun buildDescription(vararg parts: String?): String {
        return parts
            .mapNotNull { part ->
                part?.trim()?.takeIf { it.isNotBlank() }
            }
            .joinToString(separator = "\n\n")
    }

    private fun Any?.toDisplayList(): String {
        val items: List<*> = when (this) {
            null -> emptyList()
            is Iterable<*> -> this.toList()
            is Array<*> -> this.toList()
            else -> listOf(this)
        }

        return items
            .filterIsInstance<String>()
            .mapNotNull { it.trim().takeIf { value -> value.isNotBlank() } }
            .distinct()
            .joinToString(separator = ", ")
    }

    private fun cleanSynopsis(html: String): String {
        if (html.isBlank()) return ""

        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)

        doc.select("script, style, noscript").remove()

        doc.select("br").forEach { br ->
            br.replaceWith(TextNode("\n\n"))
        }

        doc.select("p, div, section, article, li").forEach { element ->
            element.before(TextNode("\n\n"))
            element.after(TextNode("\n\n"))
        }

        return doc.body()
            ?.wholeText()
            .orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\n{2,}"), "\u0000")
            .replace(Regex("\\s+"), " ")
            .replace("\u0000", "\n\n")
            .trim()
    }

    private fun buildInfoLine(vararg values: Any?): String {
        val info = values
            .mapNotNull { value ->
                value?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
            .joinToString(separator = " • ")

        return if (info.isNotBlank()) {
            "**Info:** $info"
        } else {
            "**Info:** N/A"
        }
    }

    private fun episodesText(value: Any?): String? {
        val text = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return if (text.all { it.isDigit() }) {
            if (text == "1") "1 episode" else "$text episodes"
        } else {
            text
        }
    }

    private fun durationText(value: Any?): String? {
        val text = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return if (text.all { it.isDigit() }) {
            "$text min"
        } else {
            text
        }
    }

    private fun starRatingLine(score: String?): String {
        val raw = score?.trim().orEmpty()

        if (raw.isBlank()) {
            return "☆☆☆☆☆ N/A"
        }

        if (raw.contains("★") || raw.contains("☆")) {
            return raw
        }

        val numberMatch = Regex("\\d+(?:\\.\\d+)?").find(raw) ?: return "☆☆☆☆☆ $raw"
        val numberString = numberMatch.value
        val number = numberString.toDoubleOrNull() ?: return "☆☆☆☆☆ $raw"

        val isFivePointScale = raw.contains("/5", ignoreCase = true) ||
            raw.contains("out of 5", ignoreCase = true)

        val isPercentScale = raw.contains("%") ||
            (!isFivePointScale && number > 10.0)

        val normalizedStars = when {
            isFivePointScale -> number
            isPercentScale -> number / 100.0 * 5.0
            else -> number / 10.0 * 5.0
        }

        val fullStars = normalizedStars.roundToInt().coerceIn(0, 5)
        val emptyStars = 5 - fullStars

        val starBar = "★".repeat(fullStars) + "☆".repeat(emptyStars)

        val displayScore = if (raw.contains("%")) {
            "$numberString%"
        } else {
            numberString
        }

        return "$starBar $displayScore"
    }
}
