package eu.kanade.tachiyomi.animeextension.en.masterextension

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MasterFilters {

    class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "Any", "Action", "Adventure", "Comedy", "Drama", "Fantasy", 
            "Horror", "Mystery", "Romance", "Sci-Fi", "Slice of Life", 
            "Sports", "Supernatural", "Thriller"
        )
    )

    class FormatFilter : AnimeFilter.Select<String>(
        "Format",
        arrayOf("Any", "TV", "MOVIE", "OVA", "ONA", "SPECIAL")
    )

    class SortFilter : AnimeFilter.Select<String>(
        "Sort By",
        arrayOf("Popularity", "Average Score", "Newest", "Trending")
    )

    val filterList: AnimeFilterList = AnimeFilterList(
        listOf(
            GenreFilter(),
            FormatFilter(),
            SortFilter()
        )
    )
}
