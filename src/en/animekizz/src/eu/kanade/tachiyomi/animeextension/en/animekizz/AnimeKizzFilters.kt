package eu.kanade.tachiyomi.animeextension.en.animekizz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeKizzFilters {
    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("All", ""),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Fantasy", "Fantasy"),
            Pair("Supernatural", "Supernatural"),
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : 
        AnimeFilter.Select(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
