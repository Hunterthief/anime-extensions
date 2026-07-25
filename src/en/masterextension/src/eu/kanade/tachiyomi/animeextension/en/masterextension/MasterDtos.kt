package eu.kanade.tachiyomi.animeextension.en.masterextension

import kotlinx.serialization.SerialName
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

// ======================== AllAnime DTOs ========================

@Serializable
data class AllAnimeResponse(
    val data: AllAnimeData? = null
)

@Serializable
data class AllAnimeData(
    val shows: AllAnimeShows? = null,
    val episode: AllAnimeEpisode? = null,
    val tobeparsed: String? = null
)

@Serializable
data class AllAnimeShows(
    val edges: List<AllAnimeShowEdge> = emptyList()
)

@Serializable
data class AllAnimeShowEdge(
    @SerialName("_id")
    val id: String,
    val name: String? = null
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

@Serializable
data class DecryptedEpisodeResult(
    val episode: AllAnimeEpisode? = null
)

@Serializable
data class AllAnimeVersionResponse(
    val episodeIframeHead: String? = null
)

// ======================== AllAnime Internal Hoster DTOs ========================

@Serializable
data class AllAnimeVideoLink(
    val links: List<AllAnimeLink> = emptyList()
)

@Serializable
data class AllAnimeLink(
    val link: String,
    val hls: Boolean? = null,
    val mp4: Boolean? = null,
    val dash: Boolean? = null,
    val crIframe: Boolean? = null,
    val resolutionStr: String = "",
    val subtitles: List<AllAnimeSubtitle>? = null,
    val rawUrls: AllAnimeRawUrl? = null,
    val portData: AllAnimeStream? = null
)

@Serializable
data class AllAnimeSubtitle(
    val lang: String,
    val src: String,
    val label: String? = null
)

@Serializable
data class AllAnimeStream(
    val streams: List<AllAnimeStreamObject> = emptyList()
)

@Serializable
data class AllAnimeStreamObject(
    val format: String,
    val url: String,
    val audio_lang: String = "",
    val hardsub_lang: String = ""
)

@Serializable
data class AllAnimeRawUrl(
    val vids: List<AllAnimeDashStreamObject>? = null,
    val audios: List<AllAnimeDashStreamObject>? = null
)

@Serializable
data class AllAnimeDashStreamObject(
    val bandwidth: Long,
    val height: Int,
    val url: String
)

// ======================== AnimePahe DTOs ========================

@Serializable
data class PaheSearchResponse(
    val total: Int? = null,
    val data: List<PaheSearchResult>? = null
)

@Serializable
data class PaheSearchResult(
    val id: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val snapshot: String? = null
)

@Serializable
data class PaheReleaseResponse(
    val total: Int? = null,
    val last_page: Int? = null,
    val data: List<PaheReleaseEpisode>? = null
)

@Serializable
data class PaheReleaseEpisode(
    val id: Int? = null,
    val episode: Double? = null,
    val title: String? = null,
    val snapshot: String? = null,
    val session: String? = null,
    val audio: String? = null,
    val duration: Int? = null,
    val created_at: String? = null
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
