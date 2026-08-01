package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KickAssAnimeSearchResponse(
    val result: List<KickAssAnimeSearchResult> = emptyList(),
    val maxPage: Int = 0,
)

@Serializable
data class KickAssAnimeSearchResult(
    val title: String,
    val poster: String,
    val id: Int,
)

@Serializable
data class KickAssAnimeEpisodeResponse(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
    val data: List<KickAssAnimeEpisode> = emptyList(),
)

@Serializable
data class KickAssAnimeEpisode(
    @SerialName("created_at") val createdAt: String,
    val session: String,
    @SerialName("episode") val episodeNumber: Float,
)

@Serializable
data class KickAssAnimeServerResponse(
    val servers: List<KickAssAnimeServer> = emptyList(),
)

@Serializable
data class KickAssAnimeServer(
    val name: String,
    val src: String,
)
