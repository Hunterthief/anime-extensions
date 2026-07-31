package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AniDapProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AnikageProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AniNekoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AnimeKizzProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AnimeOnsenProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AniZoneProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.SubspleaseProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.TorrentioProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AnimeGGProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.AniDBProvider
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class ProviderManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {
    // =================================================================
    // PROVIDER REGISTRY — add one line per new source
    // =================================================================
    private val allProviders: Map<String, VideoProvider> by lazy {
        linkedMapOf(
            "anidb"      to AniDBProvider(client, headers),
            "animeonsen" to AnimeOnsenProvider(client, headers),
            "anizone"    to AniZoneProvider(client, headers),
            "animegg"    to AnimeGGProvider(client, headers),
            "torrentio"  to TorrentioProvider(client, headers, "none", ""),
            "subsplease" to SubspleaseProvider(client, headers),
            "anidap"     to AniDapProvider(client, headers),
            "anikage"    to AnikageProvider(client, headers),
            "anineko"    to AniNekoProvider(client, headers),
            "animekizz"  to AnimeKizzProvider(client, headers),
            //"allanime"   to AllAnimeProvider(client, headers, preferences),
        )
    }

    val providerDisplayNames: Map<String, String> by lazy {
        allProviders.mapValues { it.value.name }
    }

    // ── NEW: base URLs for the verification-site picker ──
    val providerBaseUrls: Map<String, String> by lazy {
        allProviders.mapValues { it.value.baseUrl }
    }
    
    val defaultProviderKeys: Set<String> by lazy {
        allProviders.keys.toSet()
    }

    private fun getEnabledProviders(): List<VideoProvider> {
        val enabled = preferences.getStringSet("enabled_providers", defaultProviderKeys)
            ?: defaultProviderKeys
        return allProviders.filterKeys { it in enabled }.values.toList()
    }

    suspend fun fetchAllVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val providers = getEnabledProviders()
        if (providers.isEmpty()) return emptyList()

        val allVideos = providers.parallelCatchingFlatMap { provider ->
            provider.fetchVideos(anime, episode)
        }

        return rankVideos(allVideos.distinctBy { it.url })
    }

    private fun rankVideos(videos: List<Video>): List<Video> {
    val preferredAudio = preferences.getString("preferred_audio_type", "sub") ?: "sub"
    val preferredLang = preferences.getString("preferred_sub_lang", "en") ?: "en"
    val preferredQuality = preferences.getString("preferred_quality", "1080") ?: "1080"

    return videos.sortedWith(
        // 1st: preferred quality first
        compareByDescending<Video> {
            if (preferredQuality == "auto") 0
            else if (it.quality.contains(preferredQuality)) 1 else 0
        }.thenByDescending {
            // 2nd: resolution descending
            Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }.thenBy {
            // 3rd: audio type preference
            when {
                preferredAudio == "both" -> 0
                it.quality.contains(preferredAudio, ignoreCase = true) -> 0
                it.quality.contains("Sub", ignoreCase = true) -> 1
                it.quality.contains("Dub", ignoreCase = true) -> 2
                else -> 3
            }
        }.thenBy {
            // 4th: subtitle language preference
            when {
                preferredLang == "none" -> 0
                it.subtitleTracks.any { track ->
                    track.lang.contains(preferredLang, ignoreCase = true) ||
                    track.lang.contains(
                        java.util.Locale(preferredLang).displayLanguage,
                        ignoreCase = true
                    )
                } -> 0
                it.subtitleTracks.isEmpty() -> 1
                else -> 2
            }
        }
    )
    }

    // =================================================================
    // MAL SCRAPERS (unchanged)
    // =================================================================

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
