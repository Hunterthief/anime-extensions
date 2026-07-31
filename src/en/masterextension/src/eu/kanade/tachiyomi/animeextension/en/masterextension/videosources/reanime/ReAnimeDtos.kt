package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
