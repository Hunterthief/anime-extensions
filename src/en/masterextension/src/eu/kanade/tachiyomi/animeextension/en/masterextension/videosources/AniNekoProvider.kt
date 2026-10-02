package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.net.Uri
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AniNekoProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AniNeko"
    override val baseUrl = "https://anineko.to"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    private val nekoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val vibeRegex = Regex("""const src\s*=\s*"([^"]+)"""")

    // =================================================================
    // STEP 1: Search → slug
    // =================================================================

    private suspend fun searchSlug(title: String): String? {
        val directSlug = title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        if (verifySlug(directSlug)) return directSlug

        val titlesToTry = listOf(
            title,
            title.replace(Regex("[,;:!?]"), "").trim(),
            title.substringBefore(":").trim()
        ).distinct()

        for (searchTitle in titlesToTry) {
            val url = "$baseUrl/browse".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", searchTitle)
                .build().toString()

            try {
                val doc = client.newCall(GET(url, nekoHeaders))
                    .awaitSuccess().useAsJsoup()

                // Updated parsing logic to match the new site layout
                val cards = doc.select("article.nv-anime-card.nv-browse-card")
                val link = cards.firstNotNullOfOrNull { card ->
                    card.selectFirst("a.nv-anime-thumb") ?: card.selectFirst("a")
                }
                
                if (link != null) {
                    val href = link.attr("href")
                    val slug = href.substringAfterLast("/").substringBefore("?")
                    if (slug.isNotBlank()) return slug
                }
                
                // Fallback to old method just in case
                val oldLink = doc.selectFirst("a[href*='/watch/']") ?: continue
                val href = oldLink.attr("href")
                val slug = href.substringAfter("/watch/").substringBefore("/").substringBefore("?")
                if (slug.isNotBlank()) return slug
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private suspend fun verifySlug(slug: String): Boolean {
        return try {
            val response = client.newCall(GET("$baseUrl/watch/$slug/ep-1", nekoHeaders))
                .awaitSuccess()
            val success = response.code == 200
            response.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    // =================================================================
    // STEP 2: Watch page → extract server URLs
    // =================================================================

    private data class ServerSource(
        val dataVideo: String,
        val type: String,
        val serverName: String,
    )

    private suspend fun getServerSources(slug: String, epNum: Int): List<ServerSource> {
        val watchUrl = "$baseUrl/watch/$slug/ep-$epNum"

        val doc = try {
            client.newCall(GET(watchUrl, nekoHeaders)).awaitSuccess().useAsJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val sources = mutableListOf<ServerSource>()
        val buttons = doc.select("button.server-video")

        buttons.forEach { button ->
            val iframeUrl = button.attr("data-video")
            if (iframeUrl.isBlank()) return@forEach

            val serverName = button.ownText().trim()
            val rawType = button.selectFirst("span")?.text() ?: ""
            val versionType = when {
                rawType.contains("Soft Sub", ignoreCase = true) || rawType.contains("Sort Sub", ignoreCase = true) -> "Soft Sub"
                rawType.contains("Hard Sub", ignoreCase = true) -> "Hard Sub"
                rawType.contains("Dub", ignoreCase = true) -> "Dub"
                else -> rawType.ifBlank { "Video" }
            }

            sources.add(ServerSource(iframeUrl, versionType, serverName))
        }

        return sources
    }

    // =================================================================
    // STEP 3: Extract videos from sources
    // =================================================================

    private suspend fun extractVideosFromSource(source: ServerSource): List<Video> {
        val iframeUrl = source.dataVideo
        if (iframeUrl.isBlank()) return emptyList()

        val subtitleTracks = mutableListOf<Track>()
        runCatching {
            val uri = Uri.parse(iframeUrl)
            val subUrl = uri.getQueryParameter("sub")
                ?: uri.getQueryParameter("caption_1")
                ?: uri.getQueryParameter("c1_file")
            if (!subUrl.isNullOrBlank()) {
                val subLabel = uri.getQueryParameter("sub_1")
                    ?: uri.getQueryParameter("c1_label")
                    ?: "English"
                subtitleTracks.add(Track(subUrl, subLabel))
            }
        }

        val qualityPrefix = "${source.serverName} - ${source.type}"

        return when {
            iframeUrl.contains("vivibebe.site") || iframeUrl.contains("vibevibe.workers.dev") || iframeUrl.contains("bibiemb.xyz") -> {
                val iframeHtml = client.newCall(GET(iframeUrl, nekoHeaders)).awaitSuccess().bodyString()
                val m3u8Url = vibeRegex.find(iframeHtml)?.groupValues?.get(1)
                if (m3u8Url != null) {
                    // LocalProxy is no longer needed; PlaylistUtils handles it natively now
                    playlistUtils.extractFromHls(
                        m3u8Url,
                        referer = iframeUrl,
                        videoNameGen = { quality -> "$qualityPrefix - $quality" },
                        subtitleList = subtitleTracks,
                        masterHeaders = nekoHeaders,
                        videoHeaders = nekoHeaders,
                    )
                } else {
                    emptyList()
                }
            }

            iframeUrl.contains("otakuhg.site") || iframeUrl.contains("otakuvid.online") -> {
                vidHideExtractor.videosFromUrl(iframeUrl) { quality -> "$qualityPrefix - $quality" }.map { video ->
                    Video(
                        url = video.url,
                        quality = video.quality,
                        videoUrl = video.videoUrl,
                        headers = video.headers,
                        subtitleTracks = video.subtitleTracks + subtitleTracks,
                    )
                }
            }

            iframeUrl.contains("playmogo.com") || iframeUrl.contains("dood") -> {
                doodExtractor.videosFromUrl(iframeUrl, quality = qualityPrefix).map { video ->
                    Video(
                        url = video.url,
                        quality = video.quality,
                        videoUrl = video.videoUrl,
                        headers = video.headers,
                        subtitleTracks = video.subtitleTracks + subtitleTracks,
                    )
                }
            }

            else -> emptyList()
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val slug = searchSlug(title) ?: return emptyList()
        val sources = getServerSources(slug, meta.epNum)
        if (sources.isEmpty()) return emptyList()

        return sources.parallelCatchingFlatMap { source ->
            extractVideosFromSource(source)
        }.distinctBy { it.videoUrl }
    }
}
