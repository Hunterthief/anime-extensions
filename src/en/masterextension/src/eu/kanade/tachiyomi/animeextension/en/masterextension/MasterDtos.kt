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
data class ProviderEpisodesResponse(
    val episodes: List<ProviderEpisode> = emptyList()
)

@Serializable
data class ProviderEpisode(
    val number: Int,
    val url: String
)

@Serializable
data class ProviderServersResponse(
    val servers: List<ProviderServer> = emptyList()
)

@Serializable
data class ProviderServer(
    val name: String,
    val url: String
)
