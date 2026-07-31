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
        val meta = EpisodeMeta.from(episode)
        val debug = mutableListOf<String>()

        // Step 1: Can we build a search request?
        val searchRequest = try {
            val req = searchAnimeRequest(1, anime.title, getFilterList())
            debug.add("1-OK")
            req
        } catch (e: Exception) {
            return listOf(Video("debug://fail", "FAIL step1: ${e.message}", "debug://fail"))
        }

        // Step 2: Does the HTTP call succeed?
        val searchResponse = try {
            val resp = client.newCall(searchRequest).awaitSuccess()
            debug.add("2-OK:${resp.code}")
            resp
        } catch (e: Exception) {
            return listOf(Video("debug://fail", "FAIL step2: ${e.message}", "debug://fail"))
        }

        // Step 3: Does parsing return results?
        val searchResults = try {
            val results = searchAnimeParse(searchResponse)
            debug.add("3-OK:${results.animes.size}")
            results
        } catch (e: Exception) {
            return listOf(Video("debug://fail", "FAIL step3: ${e.message}", "debug://fail"))
        }

        if (searchResults.animes.isEmpty()) {
            return listOf(Video("debug://fail", "FAIL: search returned 0 results for '${anime.title}'", "debug://fail"))
        }

        // Step 4: Title match
        val titleLower = anime.title.lowercase().trim()
        val matchedAnime = searchResults.animes.firstOrNull {
            it.title.lowercase().trim() == titleLower
        } ?: searchResults.animes.first()

        debug.add("4-OK:${matchedAnime.title}")

        // Step 5: Episode list
        val episodes = try {
            val eps = getEpisodeList(matchedAnime)
            debug.add("5-OK:${eps.size}")
            eps
        } catch (e: Exception) {
            return listOf(Video("debug://fail", "FAIL step5: ${e.message}", "debug://fail"))
        }

        if (episodes.isEmpty()) {
            return listOf(Video("debug://fail", "FAIL: 0 episodes for '${matchedAnime.title}'", "debug://fail"))
        }

        // Step 6: Episode match
        val matchedEpisode = episodes.firstOrNull {
            it.episode_number.toInt() == meta.epNum
        } ?: episodes.getOrNull(meta.epNum - 1)

        if (matchedEpisode == null) {
            return listOf(Video("debug://fail", "FAIL: ep ${meta.epNum} not found in ${episodes.size} eps", "debug://fail"))
        }

        debug.add("6-OK:ep${matchedEpisode.episode_number}")

        // Step 7: Video extraction
        return try {
            val videos = getVideoList(matchedEpisode)
            if (videos.isEmpty()) {
                listOf(Video("debug://fail", "FAIL step7: getVideoList returned 0 videos", "debug://fail"))
            } else {
                videos
            }
        } catch (e: Exception) {
            listOf(Video("debug://fail", "FAIL step7: ${e.message}", "debug://fail"))
        }
    }
}
