package eu.kanade.tachiyomi.animeextension.en.animekizz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeKizzFilters {
    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter()
    )

    // Must be public (no 'private' modifier) so AnimeKizz.kt can access it
    class GenreFilter : UriPartFilter(
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

    // Must explicitly inherit from AnimeFilter.Select<String>
    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
