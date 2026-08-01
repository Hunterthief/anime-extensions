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
)

@Serializable
data class ReAnimeTitle(
    val english: String = "",
    val romaji: String = "",
)

@Serializable
data class ReAnimeFlixResponse(
    val success: Boolean = false,
    val servers: List<ReAnimeFlixServer> = emptyList(),
)

@Serializable
data class ReAnimeFlixServer(
    @SerialName("serverName") val serverName: String = "",
    @SerialName("dataLink") val dataLink: String = "",
    @SerialName("dataType") val dataType: String = "",
)
