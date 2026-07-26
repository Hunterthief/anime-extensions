package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AniNekoProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AniNeko"

    companion object {
        private const val BASE_URL = "https://anineko.to"
    }

    private val nekoHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

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
            val url = "$BASE_URL/browser".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", searchTitle)
                .build().toString()

            try {
                val doc = client.newCall(GET(url, nekoHeaders))
                    .awaitSuccess().useAsJsoup()

                val link = doc.selectFirst("a[href*='/watch/']") ?: continue
                val href = link.attr("href")
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
            val response = client.newCall(GET("$BASE_URL/watch/$slug/ep-1", nekoHeaders))
                .awaitSuccess()
            val success = response.code == 200
            response.close()
            success
        } catch (_: Exception) {
            false
        }
    }

    // =================================================================
    // STEP 2: Watch page → extract server URLs (skip vivibebe.site)
    // =================================================================

    private data class ServerSource(
        val dataVideo: String,
        val type: String,
        val serverName: String,
        val subtitleUrl: String?
    )

    private suspend fun getServerSources(slug: String, epNum: Int): List<ServerSource> {
        val watchUrl = "$BASE_URL/watch/$slug/ep-$epNum"

        val doc = try {
            client.newCall(GET(watchUrl, nekoHeaders)).awaitSuccess().useAsJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val sources = mutableListOf<ServerSource>()

        doc.select("div.nv-server-grid").forEach { grid ->
            val type = grid.attr("data-id")

            grid.select("button.server-video").forEach { btn ->
                val dataVideo = btn.attr("data-video")
                val serverName = btn.text().trim().substringBefore("\n").trim()

                if (dataVideo.isNotBlank()) {
                    // Skip vivibebe.site — no CORS headers, doesn't play in ExoPlayer
                    if (dataVideo.contains("vivibebe.site")) return@forEach

                    val subtitleUrl = when {
                        dataVideo.contains("sub=") ->
                            dataVideo.substringAfter("sub=").substringBefore("&").ifBlank { null }
                        dataVideo.contains("caption_1=") ->
                            dataVideo.substringAfter("caption_1=").substringBefore("&").ifBlank { null }
                        dataVideo.contains("c1_file=") ->
                            dataVideo.substringAfter("c1_file=").substringBefore("&").ifBlank { null }
                        else -> null
                    }

                    sources.add(ServerSource(dataVideo, type, serverName, subtitleUrl))
                }
            }
        }

        return sources
    }

    // =================================================================
    // STEP 3: Get m3u8 URL from each server
    // =================================================================

    private suspend fun getM3u8(source: ServerSource): Pair<String, Headers>? {
        val dataVideo = source.dataVideo
        val cleanUrl = dataVideo.substringBefore("?")

        return when {
            // ─── bibiemb.xyz (HD-2): direct construction, workers.dev CDN ───
            // access-control-allow-origin: *
            cleanUrl.contains("bibiemb.xyz") -> {
                val id = cleanUrl.substringAfter("bibiemb.xyz/").trim('/')
                val m3u8 = "https://morning-credit-3bcc.vibevibe.workers.dev/$id/master.m3u8"
                val vidHeaders = headers.newBuilder()
                    .set("Referer", "https://bibiemb.xyz/")
                    .set("Origin", "https://bibiemb.xyz")
                    .build()
                Pair(m3u8, vidHeaders)
            }

            // ─── otakuhg.site (StreamHG): fetch player page → regex m3u8 ───
            // access-control-allow-origin: https://otakuhg.site
            cleanUrl.contains("otakuhg.site") -> {
                val playerHeaders = headers.newBuilder()
                    .set("Referer", cleanUrl)
                    .set("Cookie", "lang=1")
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val playerBody = try {
                    client.newCall(GET(cleanUrl, playerHeaders)).awaitSuccess().bodyString()
                } catch (_: Exception) { return null }

                val m3u8 = Regex("""https?://[^\s"'<>\\]+\.urlset/master\.txt""").find(playerBody)?.value
                    ?: Regex("""https?://[^\s"'<>\\]+master\.txt""").find(playerBody)?.value
                    ?: Regex("""["'](https?://[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*)["']""").find(playerBody)?.groupValues?.get(1)
                    ?: return null

                val vidHeaders = headers.newBuilder()
                    .set("Referer", "https://otakuhg.site/")
                    .set("Origin", "https://otakuhg.site")
                    .build()
                Pair(m3u8, vidHeaders)
            }

            // ─── otakuvid.online (Earnvids): fetch player page → regex m3u8 ───
            // access-control-allow-origin: https://otakuvid.online
            cleanUrl.contains("otakuvid.online") -> {
                val playerHeaders = headers.newBuilder()
                    .set("Referer", cleanUrl)
                    .set("Cookie", "lang=1")
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val playerBody = try {
                    client.newCall(GET(cleanUrl, playerHeaders)).awaitSuccess().bodyString()
                } catch (_: Exception) { return null }

                val m3u8 = Regex("""https?://[^\s"'<>\\]+/master\.m3u8""").find(playerBody)?.value
                    ?: Regex("""https?://[^\s"'<>\\]+master\.txt""").find(playerBody)?.value
                    ?: Regex("""["'](https?://[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*)["']""").find(playerBody)?.groupValues?.get(1)
                    ?: return null

                val vidHeaders = headers.newBuilder()
                    .set("Referer", "https://otakuvid.online/")
                    .set("Origin", "https://otakuvid.online")
                    .build()
                Pair(m3u8, vidHeaders)
            }

            else -> null
        }
    }

    // =================================================================
    // STEP 4: Create Video directly with master m3u8
    // ExoPlayer handles the full HLS chain internally.
    // =================================================================

    private fun createVideo(
        m3u8Url: String,
        vidHeaders: Headers,
        source: ServerSource
    ): Video {
        val typeLabel = when (source.type) {
            "dub" -> "Dub"
            "sub" -> "Soft Sub"
            else -> "Hard Sub"
        }

        val subtitles = source.subtitleUrl?.let {
            listOf(Track(it, "English"))
        }.orEmpty()

        return Video(
            url = m3u8Url,
            quality = "$name ${source.serverName} $typeLabel Auto",
            videoUrl = m3u8Url,
            headers = vidHeaders,
            subtitleTracks = subtitles,
        )
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

        val allVideos = mutableListOf<Video>()

        for (source in sources) {
            try {
                val (m3u8Url, vidHeaders) = getM3u8(source) ?: continue
                allVideos.add(createVideo(m3u8Url, vidHeaders, source))
            } catch (_: Exception) {
                continue
            }
        }

        return allVideos.distinctBy { it.videoUrl }
    }
}
