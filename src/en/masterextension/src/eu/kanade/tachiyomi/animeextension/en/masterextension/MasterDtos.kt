package eu.kanade.tachiyomi.animeextension.en.masterextension

import kotlinx.serialization.Serializable

@Serializable
data class AniListResponse(
    val data: AniListPage? = null
)

@Serializable
data class AniListPage(
    val Page: AniListMediaPage? = null,
    val Media: AniListMedia? = null
)

@Serializable
data class AniListMediaPage(
    val media: List<AniListMedia> = emptyList()
)

@Serializable
data class AniListMedia(
    val id: Int,
    val idMal: Int? = null,
    val title: AniListTitle? = null,
    val description: String? = null,
    val coverImage: AniListCover? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val nextAiringEpisode: AniListNextAiring? = null
)

@Serializable
data class AniListTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)

@Serializable
data class AniListCover(
    val large: String? = null,
    val extraLarge: String? = null
)

@Serializable
data class AniListNextAiring(
    val episode: Int? = null,
    val airingAt: Long? = null
)

@Serializable
data class ConsumetEpisodesResponse(
    val episodes: List<ConsumetEpisode> = emptyList()
)

@Serializable
data class ConsumetEpisode(
    val number: Int,
    val id: String,
    val url: String? = null
)

@Serializable
data class ConsumetServersResponse(
    val sources: List<ConsumetSource> = emptyList(),
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class ConsumetSource(
    val url: String,
    val quality: String? = null,
    val isM3U8: Boolean? = null
)
