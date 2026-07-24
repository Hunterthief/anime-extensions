package eu.kanade.tachiyomi.animeextension.en.masterextension

import kotlinx.serialization.Serializable

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

// AllAnime API DTOs
@Serializable
data class AllAnimeResponse(
    val data: AllAnimeData? = null
)

@Serializable
data class AllAnimeData(
    val shows: AllAnimeShows? = null,
    val show: AllAnimeShow? = null,
    val episode: AllAnimeEpisode? = null
)

@Serializable
data class AllAnimeShows(
    val edges: List<AllAnimeShowEdge> = emptyList()
)

@Serializable
data class AllAnimeShowEdge(
    val _id: String,
    val name: String? = null
)

@Serializable
data class AllAnimeShow(
    val _id: String,
    val episodes: List<AllAnimeEpisodeInfo> = emptyList()
)

@Serializable
data class AllAnimeEpisodeInfo(
    val episodeString: String,
    val note: String? = null
)

@Serializable
data class AllAnimeEpisode(
    val sourceUrls: List<AllAnimeSourceUrl> = emptyList()
)

@Serializable
data class AllAnimeSourceUrl(
    val sourceUrl: String,
    val sourceName: String,
    val type: String? = null,
    val priority: Float? = null
)

// Internal AllAnime apivtwo DTOs
@Serializable
data class AllAnimeApivtwoResponse(
    val links: List<AllAnimeApivtwoLink> = emptyList()
)

@Serializable
data class AllAnimeApivtwoLink(
    val link: String,
    val hls: String? = null,
    val mp4: String? = null,
    val resolutionStr: String? = null,
    val src: String? = null
)
