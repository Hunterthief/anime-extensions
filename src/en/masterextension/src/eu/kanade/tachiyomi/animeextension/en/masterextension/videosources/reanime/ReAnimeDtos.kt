package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReAnimeSearchResponse(
    val results: List<ReAnimeSearchResult> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ReAnimeSearchResult(
    @SerialName("anime_id") val animeId: String = "",
    val title: ReAnimeTitle = ReAnimeTitle(),
    @SerialName("cover_image") val coverImage: ReAnimeCoverImage = ReAnimeCoverImage(),
    val format: String = "",
    val episodes: Int = 0,
    val subbed: Int = 0,
    val dubbed: Int = 0,
    @SerialName("average_score") val averageScore: Int = 0,
    val genres: List<String> = emptyList(),
    @SerialName("season_year") val seasonYear: Int = 0,
)

@Serializable
data class ReAnimeTitle(
    val english: String = "",
    val native: String = "",
    val romaji: String = "",
)

@Serializable
data class ReAnimeCoverImage(
    @SerialName("extra_large") val extraLarge: String = "",
    val large: String = "",
    val medium: String = "",
)
