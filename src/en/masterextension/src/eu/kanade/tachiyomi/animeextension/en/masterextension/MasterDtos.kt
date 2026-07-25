package eu.kanade.tachiyomi.animeextension.en.masterextension

import kotlinx.serialization.Serializable

// ======================== AniList DTOs ========================

@Serializable
data class AniListMediaData(
    val Media: AniListMedia? = null,
    val Page: AniListPage? = null
)

@Serializable
data class AniListPage(
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
    val duration: Int? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val studios: AniListStudios? = null,
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
data class AniListStudios(
    val nodes: List<AniListNode>? = null
)

@Serializable
data class AniListNode(
    val name: String? = null,
    val isAnimationStudio: Boolean? = null
)

@Serializable
data class AniListNextAiring(
    val episode: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null
)

// ======================== MAL Helper DTOs ========================

data class MalEpisode(
    val number: String,
    val title: String,
    val date: Long
)

data class MalAnimeDetails(
    val score: String,
    val rating: String,
    val synopsis: String,
    val type: String,
    val episodes: String,
    val duration: String,
    val premiered: String
)
