package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm

import kotlinx.serialization.Serializable

@Serializable
class CatalogResponseDto(val items: List<TitleItemDto> = emptyList())

@Serializable
class TitleItemDto(val id: Long? = null, val title: String? = null)

@Serializable
class SeriesResponseDto(val id: Long, val episodes: List<SeriesEpisodeDto> = emptyList())

@Serializable
class SeriesEpisodeDto(
    val number: Double,
    val sub: Boolean = false,
    val dub: Boolean = false,
    val subhard: Boolean = false,
    val dubhard: Boolean = false,
    val routeId: String? = null,
)

@Serializable
class BootstrapDto(val settlarSelection: String? = null)

@Serializable
class SettlarSessionDto(val embedUrl: String? = null)

@Serializable
class EmbedSessionDto(val source: String? = null, val subtitles: List<EmbedSubtitleDto> = emptyList())

@Serializable
class EmbedSubtitleDto(val url: String? = null, val label: String? = null, val srclang: String? = null)

internal fun fmtNum(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
