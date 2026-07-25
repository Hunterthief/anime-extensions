package eu.kanade.tachiyomi.animeextension.en.masterextension

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import java.net.URLDecoder

/**
 * Contract for all video sources.
 *
 * To add a new source:
 *   1. Create a file in video_sources/ that implements this interface
 *   2. Add one line to ProviderManager.allProviders
 *   3. Done. Nothing else changes.
 */
interface VideoProvider {
    /** Shown in video quality labels, e.g. "Anikoto - 1080p" */
    val name: String

    /**
     * Return all playable [Video]s for this episode.
     * Return emptyList() on failure — never throw.
     */
    suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video>
}

/**
 * Parsed from SEpisode.url: "$anilistId/$malId/$epNum/$urlEncodedTitle"
 * Every provider gets this for free.
 */
data class EpisodeMeta(
    val anilistId: Int,
    val malId: Int,
    val epNum: Int,
    val title: String
) {
    companion object {
        fun from(episode: SEpisode): EpisodeMeta {
            val parts = episode.url.split("/", limit = 4)
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
