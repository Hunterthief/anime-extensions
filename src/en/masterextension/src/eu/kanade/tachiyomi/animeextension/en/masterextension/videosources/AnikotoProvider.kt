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

    // Regex to catch "Season 1", "Part 2", "2nd Season", etc.
    private val seasonNumberRegex = Regex(
        """(?:season|part)\s*(\d+)|(\d+)(?:st|nd|rd|th)\s*(?:season|part)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractSeasonNumber(text: String): Int? {
        val match = seasonNumberRegex.find(text) ?: return null
        val numStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return numStr.toIntOrNull()
    }

    private fun stripSeasonInfo(title: String): String {
        return title
            .replace(Regex("""\s*[-:]\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*(?:season|part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+(?:st|nd|rd|th)\s*(?:season|part).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            // 1. If the master extension already provided a valid Anikoto URL, use it directly!
            val targetAnime = if (anime.url.isNotBlank() && anime.url.contains("anikoto", ignoreCase = true)) {
                anime
            } else {
                // 2. Otherwise, search and use smart scoring to find the best match
                val searchRequest = searchAnimeRequest(1, anime.title, getFilterList())
                val searchResponse = client.newCall(searchRequest).awaitSuccess()
                val searchResults = searchAnimeParse(searchResponse)

                if (searchResults.animes.isEmpty()) {
                    return listOf(Video("debug://x", "0 results for '${anime.title}'", "debug://x"))
                }

                val requestedTitleLower = anime.title.lowercase().trim()
                val querySeason = extractSeasonNumber(requestedTitleLower)
                val baseTitle = stripSeasonInfo(requestedTitleLower)

                // Score each result to find the best match
                searchResults.animes.maxByOrNull { result ->
                    val resTitleLower = result.title.lowercase().trim()
                    val resSeason = extractSeasonNumber(resTitleLower)
                    val resBase = stripSeasonInfo(resTitleLower)
                    
                    var score = 0
                    
                    // Exact match is the holy grail
                    if (resTitleLower == requestedTitleLower) {
                        score += 100
                    } else if (resBase == baseTitle) {
                        score += 50 // Base title matches (e.g., "Frieren" matches "Frieren Season 2")
                        
                        if (querySeason != null && resSeason == querySeason) {
                            score += 20 // Bonus for matching the correct season
                        } else if (querySeason != null && resSeason != null) {
                            score -= 10 // Penalty for matching the WRONG season
                        }
                    }
                    
                    // Small bonus if it at least contains the requested string
                    if (resTitleLower.contains(requestedTitleLower)) {
                        score += 5
                    }
                    
                    score
                } ?: searchResults.animes.first() // Only fall back to first() if scoring somehow yields null
            }

            val episodes = getEpisodeList(targetAnime)
            if (episodes.isEmpty()) {
                return listOf(Video("debug://x", "0 eps for '${targetAnime.title}'", "debug://x"))
            }

            val meta = EpisodeMeta.from(episode)
            
            // Safely match the episode number (cast Float to Int to prevent crashes)
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
