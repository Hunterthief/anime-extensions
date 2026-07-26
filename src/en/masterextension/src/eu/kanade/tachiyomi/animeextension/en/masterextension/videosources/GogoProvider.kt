package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GogoProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "GogoAnime"

    companion object {
        // anitaku.to is dead. anitaku.com.ro is the working domain as of July 2026.
        private val DOMAINS = listOf(
            "https://anitaku.com.ro",
            "https://gogoanime3.net",
            "https://anitaku.to",
        )
    }

    private val extractor by lazy { GogoStreamExtractor(client) }

    private fun gogoHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    // =================================================================
    // STEP 1: Search by title → category slug
    // =================================================================

    private suspend fun searchAnime(title: String): Pair<String, String>? {
        for (domain in DOMAINS) {
            try {
                // FIX #1: Use raw title as keyword — do NOT slugify
                val url = "$domain/search.html".toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", title)
                    .build().toString()

                val doc = client.newCall(GET(url, gogoHeaders(domain)))
                    .awaitSuccess().asJsoup()

                // FIX #2: Specific selector for the name link inside search results
                val result = doc.selectFirst("ul.items li p.name a")
                    ?: doc.selectFirst("div.last_recent ul li p.name a")
                    ?: doc.selectFirst("a[href*=/category/]")

                val href = result?.attr("href") ?: continue
                val slug = href.substringAfter("/category/").substringBefore("?")
                if (slug.isNotBlank()) return domain to slug
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Episode page → Vidstreaming iframe URL
    // =================================================================

    private suspend fun getServerUrl(baseUrl: String, slug: String, epNum: Int): String? {
        val epUrl = "$baseUrl/$slug-episode-$epNum"

        return try {
            val doc = client.newCall(GET(epUrl, gogoHeaders(baseUrl)))
                .awaitSuccess().asJsoup()

            // Primary: vidcdn server (Vidstreaming)
            val vidcdn = doc.selectFirst("div.anime_muti_link ul li.vidcdn a")
            val vidcdnUrl = vidcdn?.attr("data-video")
            if (!vidcdnUrl.isNullOrBlank()) {
                return if (vidcdnUrl.startsWith("//")) "https:$vidcdnUrl" else vidcdnUrl
            }

            // Fallback: any server with data-video
            val anyServer = doc.selectFirst("div.anime_muti_link ul li a[data-video]")
            val anyUrl = anyServer?.attr("data-video")
            if (!anyUrl.isNullOrBlank()) {
                return when {
                    anyUrl.startsWith("//") -> "https:$anyUrl"
                    anyUrl.startsWith("http") -> anyUrl
                    else -> "https://$anyUrl"
                }
            }

            // Last resort: iframe embed
            val iframe = doc.selectFirst("div.play-video iframe[src]")
            val src = iframe?.attr("abs:src")
            if (!src.isNullOrBlank()) return src

            null
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        return try {
            val (domain, slug) = searchAnime(title) ?: return emptyList()
            val serverUrl = getServerUrl(domain, slug, meta.epNum) ?: return emptyList()
            extractor.videosFromUrl(serverUrl)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
