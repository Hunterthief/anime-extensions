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
            val meta = EpisodeMeta.from(episode)

            // 1. Search — use getFilterList() so AnikotoThemeFilters can
            //    extract its typed filters. An empty AnimeFilterList() throws
            //    NoSuchElementException inside getSearchParameters().
            val searchRequest = searchAnimeRequest(1, anime.title, getFilterList())
            val searchResponse = client.newCall(searchRequest).awaitSuccess()
            val searchResults = searchAnimeParse(searchResponse)

            // 2. Match anime by title
            val titleLower = anime.title.lowercase().trim()
            val matchedAnime = searchResults.animes.firstOrNull {
                it.title.lowercase().trim() == titleLower
            } ?: searchResults.animes.minByOrNull {
                val n = it.title.lowercase().trim()
                when {
                    n.startsWith(titleLower) -> n.length
                    titleLower.startsWith(n) -> n.length + 1000
                    n.contains(titleLower) -> n.length + 2000
                    else -> Int.MAX_VALUE
                }
            } ?: return emptyList()

            // 3. Get episodes
            val episodes = getEpisodeList(matchedAnime)
            if (episodes.isEmpty()) return emptyList()

            // 4. Match episode by number
            val matchedEpisode = episodes.firstOrNull {
                it.episode_number.toInt() == meta.epNum
            } ?: episodes.getOrNull(meta.epNum - 1) ?: return emptyList()

            // 5. Get videos
            getVideoList(matchedEpisode)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
