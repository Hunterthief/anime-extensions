package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.network.awaitSuccess

class AnikotoProvider :
    AnikotoTheme(
        "en",
        "Anikoto",
        domainEntries = listOf(
            "anikototv.to",
            "anikoto.bz",
            "anikoto.cz",
            "anikoto.me",
            "anikoto.net",
            "anikototv.se",
        ),
        hosterNames = listOf("HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream", "VidPlay-1"),
    ),
    VideoProvider {

        override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val searchRequest = searchAnimeRequest(1, anime.title, getFilterList())
            val searchResponse = client.newCall(searchRequest).awaitSuccess()
            val searchResults = searchAnimeParse(searchResponse)

            if (searchResults.animes.isEmpty()) {
                return listOf(Video("debug://x", "0 results for '${anime.title}'", "debug://x"))
            }

            val titleLower = anime.title.lowercase().trim()
            val matchedAnime = searchResults.animes.firstOrNull {
                it.title.lowercase().trim() == titleLower
            } ?: searchResults.animes.first()

            val episodes = getEpisodeList(matchedAnime)
            if (episodes.isEmpty()) {
                return listOf(Video("debug://x", "0 eps for '${matchedAnime.title}'", "debug://x"))
            }

            val meta = EpisodeMeta.from(episode)
            val matchedEpisode = episodes.firstOrNull {
                it.episode_number.toInt() == meta.epNum
            } ?: episodes.getOrNull(meta.epNum - 1) ?: return listOf(
                Video("debug://x", "ep${meta.epNum} not in ${episodes.size}", "debug://x"),
            )

            val videos = getVideoList(matchedEpisode)
            if (videos.isEmpty()) {
                return listOf(Video("debug://x", "0 videos from player", "debug://x"))
            }
            videos
        } catch (t: Throwable) {
            listOf(
                Video(
                    "debug://x",
                    "${t::class.simpleName}: ${t.message?.take(120)}",
                    "debug://x",
                ),
            )
        }
    }
}
