package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaheResponseDto<T>(
    @SerialName("current_page")
    val currentPage: Int,
    @SerialName("last_page")
    val lastPage: Int,
    @EncodeDefault
    @SerialName("data")
    val items: List<T> = emptyList(),
)

@Serializable
data class PaheSearchResultDto(
    val title: String,
    val poster: String,
    val id: Int,
)

@Serializable
data class PaheEpisodeDto(
    @SerialName("created_at")
    val createdAt: String,
    val session: String,
    @SerialName("episode")
    val episodeNumber: Float,
)
