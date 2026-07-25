package eu.kanade.tachiyomi.animeextension.en.masterextension

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import java.net.URLDecoder

interface VideoProvider {
    val name: String
    suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video>
}

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
