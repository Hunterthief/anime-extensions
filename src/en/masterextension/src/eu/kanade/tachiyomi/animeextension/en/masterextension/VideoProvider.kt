package eu.kanade.tachiyomi.animeextension.en.masterextension

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import java.net.URLDecoder

/**
 * Common interface for all streaming providers.
 * Each implementation resolves the show on its respective site using
 * the anime title, AniList ID, or MAL ID (all available via [EpisodeMeta]).
 */
interface VideoProvider {
    /** Display name shown in preferences and video quality labels. */
    val name: String

    /**
     * Fetch all available [Video] sources for the given [anime] and [episode].
     * Implementations must handle their own ID resolution internally.
     * Should never throw — return [emptyList] on failure so other providers can still succeed.
     */
    suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video>
}

/**
 * Parsed from the SEpisode.url string: "$anilistId/$malId/$epNum/$urlEncodedTitle"
 * Gives providers everything they need without extra network round-trips.
 */
data class EpisodeMeta(
    val anilistId: Int,
    val malId: Int,
    val epNum: Int,
    val title: String
) {
    companion object {
        fun from(episode: SEpisode): EpisodeMeta {
            val parts = episode.url.split("/")
            return EpisodeMeta(
                anilistId = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                malId = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                epNum = parts.getOrNull(2)?.toIntOrNull()
                    ?: episode.episode_number.toInt(),
                title = parts.getOrNull(3)?.let {
                    try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { "" }
                } ?: ""
            )
        }
    }
}
