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

    // Helper to normalize titles: lowercase, remove punctuation, collapse spaces
    private fun String.normalize(): String {
        return this.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove all punctuation
            .replace(Regex("\\s+"), " ")        // Collapse multiple spaces into one
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            // 1. If the master extension already provided a valid Anikoto URL, use it directly!
            val targetAnime = if (anime.url.isNotBlank() && anime.url.contains("anikoto", ignoreCase = true)) {
                anime
            } else {
                // 2. Otherwise, search and use normalized scoring to find the best match
                val searchRequest = searchAnimeRequest(1, anime.title, getFilterList())
                val searchResponse = client.newCall(searchRequest).awaitSuccess()
                val searchResults = searchAnimeParse(searchResponse)

                if (searchResults.animes.isEmpty()) {
                    return listOf(Video("debug://x", "0 results for '${anime.title}'", "debug://x"))
                }

                val requestedTitleLower = anime.title.lowercase().trim()
                
                // Fail fast if the title is somehow empty
                if (requestedTitleLower.isBlank()) {
                    return listOf(Video("debug://x", "Anime title is missing", "debug://x"))
                }

                val cleanRequested = requestedTitleLower.normalize()
                val querySeason = extractSeasonNumber(requestedTitleLower)
                val baseTitle = stripSeasonInfo(requestedTitleLower).normalize()

                val scoredResults = searchResults.animes.map { result ->
                    val resTitleLower = result.title.lowercase().trim()
                    val cleanRes = resTitleLower.normalize()
                    val resSeason = extractSeasonNumber(resTitleLower)
                    val resBase = stripSeasonInfo(resTitleLower).normalize()
                    
                    var score = 0
                    
                    // 1. Exact normalized match (ignores punctuation/capitalization)
                    if (cleanRes == cleanRequested) {
                        score += 1000
                    } 
                    // 2. Contains match
                    else if (cleanRes.contains(cleanRequested)) {
                        score += 800
                    } 
                    // 3. Reverse contains match (e.g., query is longer than site title)
                    else if (cleanRequested.contains(cleanRes)) {
                        score += 600
                    }
                    // 4. Base title match (handles "Title Season 2" vs "Title")
                    else if (resBase == baseTitle) {
                        score += 500
                        if (querySeason != null && resSeason == querySeason) {
                            score += 100 // Bonus for matching the correct season
                        } else if (querySeason != null && resSeason != null) {
                            score -= 50 // Penalty for matching the WRONG season
                        }
                    }
                    
                    // 5. Word overlap bonus (gives points for sharing key words)
                    val reqWords = cleanRequested.split(" ").filter { it.length > 2 }
                    val resWords = cleanRes.split(" ").filter { it.length > 2 }
                    val matchingWords = reqWords.count { reqWord -> 
                        resWords.any { resWord -> resWord.contains(reqWord) || reqWord.contains(resWord) }
                    }
                    score += matchingWords * 20
                    
                    Pair(result, score)
                }
                
                // Filter out completely unrelated results (score <= 0 means no meaningful overlap)
                val validMatches = scoredResults.filter { it.second > 0 }
                val bestMatch = validMatches.maxByOrNull { it.second }?.first
                
                if (bestMatch == null) {
                    return listOf(Video("debug://x", "No close match found for '${anime.title}'", "debug://x"))
                }
                
                bestMatch
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
                    "${t::class.simpleName}: ${t.message?.take(115)}",
                    "debug://x",
                ),
            )
        }
    }
}
