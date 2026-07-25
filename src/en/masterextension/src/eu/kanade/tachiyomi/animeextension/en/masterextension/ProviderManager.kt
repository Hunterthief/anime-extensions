package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences
) {
    private val allProviders: Map<String, VideoProvider> by lazy {
        linkedMapOf(
            "allanime"  to AllAnimeProvider(client, headers),
            "gogoanime" to GogoanimeProvider(client, headers),
            "animepahe" to AnimePaheProvider(client, headers)
        )
    }

    val providerDisplayNames: Map<String, String> by lazy {
        linkedMapOf(
            "allanime"  to "AllAnime",
            "gogoanime" to "Gogoanime (Anitaku)",
            "animepahe" to "AnimePahe"
        )
    }

    val defaultProviderKeys: Set<String> = setOf("allanime")

    fun getEnabledProviders(): List<VideoProvider> {
        val enabled = preferences.getStringSet("enabled_providers", defaultProviderKeys)
            ?: defaultProviderKeys
        return allProviders.filterKeys { it in enabled }.values.toList()
    }

    suspend fun fetchAllVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val providers = getEnabledProviders()
        if (providers.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val deferred = providers.map { provider ->
                async {
                    try {
                        provider.fetchVideos(anime, episode)
                    } catch (_: Exception) {
                        emptyList<Video>()
                    }
                }
            }

            val allVideos = deferred.awaitAll().flatten()
            val deduplicated = allVideos.distinctBy { it.url }
            rankVideos(deduplicated)
        }
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
        val preferredSubType = preferences.getString("preferred_sub_type", "softsub") ?: "softsub"
        return videos.sortedWith(
            compareByDescending<Video> {
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.thenBy {
                when {
                    it.quality.contains(preferredSubType, ignoreCase = true) -> 0
                    it.quality.contains("softsub", ignoreCase = true) -> 1
                    it.quality.contains("hardsub", ignoreCase = true) -> 2
                    it.quality.contains("dub", ignoreCase = true) -> 3
                    else -> 4
                }
            }
        )
    }

    // ======================== MAL Scrapers ========================

    fun fetchMalAnimeDetails(malId: Int): MalAnimeDetails? {
        return try {
            val request = Request.Builder()
                .url("https://myanimelist.net/anime/$malId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://myanimelist.net/")
                .get()
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val document = Jsoup.parse(res.body.string())

                val score = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim() ?: ""
                val rating = document.select("div.spaceit_pad").firstOrNull { it.text().startsWith("Rating:") }
                    ?.ownText()?.replace("Rating:", "")?.trim() ?: ""
                val synopsis = document.selectFirst("p[itemprop=description]")?.text()?.trim() ?: ""
                val type = document.select("div.spaceit_pad").firstOrNull { it.text().startsWith("Type:") }
                    ?.selectFirst("a")?.text()?.trim() ?: ""
                val episodes = document.select("div.spaceit_pad").firstOrNull { it.text().startsWith("Episodes:") }
                    ?.ownText()?.replace("Episodes:", "")?.trim() ?: ""
                val duration = document.select("div.spaceit_pad").firstOrNull { it.text().startsWith("Duration:") }
                    ?.ownText()?.replace("Duration:", "")?.trim() ?: ""
                val premiered = document.select("div.spaceit_pad").firstOrNull { it.text().startsWith("Premiered:") }
                    ?.selectFirst("a")?.text()?.trim() ?: ""

                MalAnimeDetails(score, rating, synopsis, type, episodes, duration, premiered)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchMalEpisodes(malId: Int): Triple<List<MalEpisode>, String, String> {
        return try {
            val request = Request.Builder()
                .url("https://myanimelist.net/anime/$malId/_/episode")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://myanimelist.net/")
                .get()
                .build()

            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) return Triple(emptyList(), "M0", "ERR:${res.code}")

                val document = Jsoup.parse(bodyStr)
                val episodeRows = document.select("tr.episode-list-data")
                if (episodeRows.isEmpty()) return Triple(emptyList(), "M0", "Empty")

                val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

                val episodes = episodeRows.mapNotNull { row ->
                    val numberStr = row.selectFirst("td.episode-number")?.attr("data-raw")?.trim()
                        ?: row.selectFirst("td.episode-number")?.text()?.trim()?.replace(Regex("[^0-9]"), "")
                    val title = row.selectFirst("a.fl-l.fw-b")?.text()?.trim() ?: ""
                    val dateStr = row.selectFirst("td.episode-aired")?.text()?.trim()
                    val dateMillis = try { dateFormatter.parse(dateStr)?.time ?: 0L } catch (_: Exception) { 0L }

                    if (!numberStr.isNullOrBlank() && title.isNotEmpty()) {
                        MalEpisode(numberStr, title, dateMillis)
                    } else null
                }

                if (episodes.isNotEmpty()) Triple(episodes, "M1", "")
                else Triple(emptyList(), "M0", "ParseFail")
            }
        } catch (_: Exception) {
            Triple(emptyList(), "M0", "EXC")
        }
    }
}
