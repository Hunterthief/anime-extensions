package eu.kanade.tachiyomi.animeextension.en.yomi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

public abstract class SelectFilter(
    displayName: String,
    private val options: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    displayName,
    options.map { it.first }.toTypedArray(),
) {
    public val selectedValue: String
        get() = options[state].second
}

public class GenreFilter : SelectFilter(
    "Genre",
    arrayOf(
        Pair("All", ""),
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Comedy", "Comedy"),
        Pair("Drama", "Drama"),
        Pair("Fantasy", "Fantasy"),
        Pair("Horror", "Horror"),
        Pair("Romance", "Romance"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Supernatural", "Supernatural")
    )
)

public class FormatFilter : SelectFilter(
    "Format",
    arrayOf(
        Pair("All", ""),
        Pair("TV Series", "TV"),
        Pair("Movies", "MOVIE"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA")
    )
)

public class SortFilter : SelectFilter(
    "Sort By",
    arrayOf(
        Pair("Trending", "TRENDING_DESC"),
        Pair("Highest Rated", "SCORE_DESC"),
        Pair("Newest First", "START_DATE_DESC"),
        Pair("Most Episodes", "EPISODES_DESC")
    )
)

public object YomiFilters {
    public fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            SortFilter(),
            GenreFilter(),
            FormatFilter()
        )
    }
}
