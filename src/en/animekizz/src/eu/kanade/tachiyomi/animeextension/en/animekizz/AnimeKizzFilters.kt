package eu.kanade.tachiyomi.animeextension.en.animekizz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

abstract class SelectFilter(
    displayName: String,
    private val options: Array<Pair<String, String>>,
) : AnimeFilter.Select(
    displayName,
    options.map { it.first }.toTypedArray(),
) {
    val selectedValue: String
        get() = options[state].second
}

class GenreFilter : SelectFilter(
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

object AnimeKizzFilters {
    fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            GenreFilter(),
        )
    }
}
