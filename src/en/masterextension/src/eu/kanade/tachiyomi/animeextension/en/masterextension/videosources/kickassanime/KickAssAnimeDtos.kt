package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.kickassanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SearchResponseDto(
    val result: List<PopularItemDto>,
    val maxPage: Int,
)

@Serializable
data class PopularItemDto(
    val title: String,
    val title_en: String?,
    val slug: String,
    val poster: PosterDto,
)

@Serializable
data class PosterDto(@SerialName("hq") val slug: String) {
    val url by lazy { "image/poster/$slug.webp" }
}

@Serializable
data class EpisodeResponseDto(
    val pages: List<JsonObject>,
    val result: List<EpisodeDto> = emptyList(),
) {
    @Serializable
    data class EpisodeDto(
        val slug: String,
        val title: String?,
        val episode_string: String,
    )
}

@Serializable
data class ServersDto(val servers: List<Server>) {
    @Serializable
    data class Server(
        val name: String,
        val src: String,
    )
}

@Serializable
data class VideoDto(
    val hls: String = "",
    val dash: String = "",
    val subtitles: List<SubtitlesDto> = emptyList(),
) {
    @Serializable
    data class SubtitlesDto(val name: String, val language: String, val src: String)
}
