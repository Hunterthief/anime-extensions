package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.util.Log
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
        private const val TAG = "EpisodeMeta"

        fun from(episode: SEpisode): EpisodeMeta {
            val rawUrl = episode.url
            Log.d(TAG, "Parsing episode URL: '$rawUrl'")

            // Use limit=4 so the title (which may contain encoded slashes %2F)
            // is never split further.
            val parts = rawUrl.split("/", limit = 4)
            Log.d(TAG, "Split into ${parts.size} parts: $parts")

            val meta = EpisodeMeta(
                anilistId = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                malId = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                epNum = parts.getOrNull(2)?.toIntOrNull()
                    ?: episode.episode_number.toInt(),
                title = parts.getOrNull(3)?.let {
                    try {
                        URLDecoder.decode(it, "UTF-8")
                    } catch (e: Exception) {
                        Log.e(TAG, "URLDecoder failed for '$it'", e)
                        ""
                    }
                } ?: ""
            )

            Log.d(TAG, "Parsed meta: anilistId=${meta.anilistId}, malId=${meta.malId}, " +
                "epNum=${meta.epNum}, title='${meta.title}'")

            if (meta.title.isBlank()) {
                Log.w(TAG, "WARNING: title is blank! Providers will abort early.")
            }

            return meta
        }
    }
}
