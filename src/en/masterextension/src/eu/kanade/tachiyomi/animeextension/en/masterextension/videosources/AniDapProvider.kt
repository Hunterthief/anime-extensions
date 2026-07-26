package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AniDapProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AniDap"

    companion object {
        private const val SITE_URL = "https://anidap.lol"
        private const val API_URL = "https://chad.anidap.lol"

        private val PROVIDERS = listOf("mimi", "kiwi", "sora")
        private val TYPES = listOf("sub", "dub")

        // Matches: "id","liar-liar-nnm8r","anilistId"
        private val SLUG_REGEX = Regex(""""id","([a-z0-9]+-[a-z0-9]+-[a-z0-9]{5,})","anilistId"""")
        // Fallback: any slug-like string near "id"
        private val SLUG_REGEX_ALT = Regex(""""id","([a-z]+-[a-z]+-[a-z0-9]{5,})"""")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Cookie jar captures the _amx_id session token
    private val cookieJar = DapCookieJar()
    private val cookieClient by lazy {
        client.newBuilder().cookieJar(cookieJar).build()
    }

    private class DapCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
            this.cookies.addAll(cookies)
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies.filter { it.matches(url) }
        }
    }

    private val siteHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$SITE_URL/")
            .set("Origin", SITE_URL)
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", "$SITE_URL/")
            .set("Origin", SITE_URL)
            .build()
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class SourcesResponse(
        val sources: List<SourceEntry> = emptyList(),
        val tracks: List<TrackEntry>? = null,
        val headers: Map<String, String>? = null
    )

    @Serializable
    private data class SourceEntry(
        val url: String = "",
        val quality: String = "auto",
        val type: String = ""
    )

    @Serializable
    private data class TrackEntry(
        val url: String = "",
        val lang: String = "",
        val label: String = "",
        val kind: String = ""
    )

    // =================================================================
    // STEP 1: Fetch watch page → extract slug
    // Also initializes the _amx_id session cookie
    // =================================================================

    private suspend fun resolveSlug(anilistId: Int, epNum: Int): String? {
        val watchUrl = "$SITE_URL/watch?id=$anilistId&ep=$epNum&type=sub&provider=mimi"

        val html = try {
            cookieClient.newCall(GET(watchUrl, siteHeaders))
                .awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        SLUG_REGEX.find(html)?.groupValues?.get(1)?.let { return it }
        SLUG_REGEX_ALT.find(html)?.groupValues?.get(1)?.let { return it }

        return null
    }

    // =================================================================
    // STEP 2: Call sources API → get m3u8 URL
    // =================================================================

    private suspend fun fetchSources(
        slug: String,
        epNum: Int,
        type: String,
        providerId: String
    ): SourcesResponse? {
        val url = "$API_URL/rest/api/sources".toHttpUrl().newBuilder()
            .addQueryParameter("id", slug)
            .addQueryParameter("epNum", epNum.toString())
            .addQueryParameter("type", type)
            .addQueryParameter("providerId", providerId)
            .build().toString()

        return try {
            cookieClient.newCall(GET(url, apiHeaders))
                .awaitSuccess().bodyString()
                .parseAs<SourcesResponse>()
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: m3u8 → Videos via PlaylistUtils
    // =================================================================

    private suspend fun extractVideos(
        response: SourcesResponse,
        providerId: String,
        type: String
    ): List<Video> {
        val m3u8Url = response.sources.firstOrNull { it.url.isNotBlank() }?.url
            ?: return emptyList()

        if (!m3u8Url.startsWith("http")) return emptyList()

        val typeLabel = if (type == "dub") "Dub" else "Sub"

        // Use anidap.lol as referer (matches what the browser sends)
        val vidHeaders = headers.newBuilder()
            .set("Referer", "$SITE_URL/")
            .set("Origin", SITE_URL)
            .build()

        val subtitles = response.tracks
            ?.filter { it.kind == "captions" && it.url.isNotBlank() }
            ?.map { Track(it.url, it.label.ifBlank { it.lang }) }
            .orEmpty()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "$name $providerId $typeLabel $quality" },
            subtitleList = subtitles,
            referer = "$SITE_URL/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        if (meta.anilistId == 0) return emptyList()

        // Step 1: Get slug (also sets session cookie)
        val slug = resolveSlug(meta.anilistId, meta.epNum) ?: return emptyList()

        // Step 2+3: Try each type and provider until we get videos
        for (type in TYPES) {
            for (provider in PROVIDERS) {
                val response = fetchSources(slug, meta.epNum, type, provider) ?: continue
                if (response.sources.isEmpty()) continue

                val videos = extractVideos(response, provider, type)
                if (videos.isNotEmpty()) return videos
            }
        }

        return emptyList()
    }
}
