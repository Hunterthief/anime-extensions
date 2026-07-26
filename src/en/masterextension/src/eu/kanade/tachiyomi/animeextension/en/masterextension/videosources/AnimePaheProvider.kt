package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AnimePahe"

    companion object {
        private const val BASE_URL = "https://animepahe.pw"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val paheHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private val pageHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val kwikHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class SearchResponse(
        val data: List<SearchResult> = emptyList()
    )

    @Serializable
    private data class SearchResult(
        val id: Int = 0,
        val title: String = ""
    )

    @Serializable
    private data class ReleaseResponse(
        @SerialName("last_page")
        val lastPage: Int = 1,
        val data: List<ReleaseEpisode> = emptyList()
    )

    @Serializable
    private data class ReleaseEpisode(
        val episode: Int = 0,
        val session: String = ""
    )

    private data class KwikSource(
        val url: String,
        val resolution: String,
        val audio: String,
        val fansub: String
    )

    // =================================================================
    // STEP 1: Search → anime ID
    // =================================================================

    private suspend fun searchAnime(title: String): SearchResult? {
        // Try exact title first, then simplified
        val titlesToTry = listOf(
            title,
            title.replace(Regex("[,;:!?]"), "").trim(),
            title.substringBefore(":").trim(),
            title.substringBefore(",").trim()
        ).distinct()

        for (searchTitle in titlesToTry) {
            val url = "$BASE_URL/api".toHttpUrl().newBuilder()
                .addQueryParameter("m", "search")
                .addQueryParameter("q", searchTitle)
                .build().toString()

            try {
                val result = client.newCall(GET(url, paheHeaders))
                    .awaitSuccess().bodyString()
                    .parseAs<SearchResponse>()
                    .data.firstOrNull()

                if (result != null) return result
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Anime page → UUID (parse from HTML, not redirect)
    // =================================================================

    private suspend fun getAnimeUuid(animeId: Int): String? {
        val html = try {
            client.newCall(GET("$BASE_URL/anime/$animeId", pageHeaders))
                .awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        // Look for /play/$uuid/ or /anime/$uuid in the page HTML
        val uuidRegex = Regex("""/play/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/""")
        uuidRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: /anime/$uuid link
        val animeUuidRegex = Regex("""/anime/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""")
        animeUuidRegex.find(html)?.groupValues?.get(1)?.let { return it }

        return null
    }

    // =================================================================
    // STEP 3: Release API → episode session
    // =================================================================

    private suspend fun getEpisodeSession(animeId: Int, epNum: Int): String? {
        var page = 1
        var lastPage = 1

        while (page <= lastPage) {
            val url = "$BASE_URL/api".toHttpUrl().newBuilder()
                .addQueryParameter("m", "release")
                .addQueryParameter("id", animeId.toString())
                .addQueryParameter("sort", "episode_asc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("l", "30")
                .build().toString()

            try {
                val release = client.newCall(GET(url, paheHeaders))
                    .awaitSuccess().bodyString()
                    .parseAs<ReleaseResponse>()

                lastPage = release.lastPage
                val ep = release.data.firstOrNull { it.episode == epNum }
                if (ep != null) return ep.session
            } catch (_: Exception) {
                return null
            }
            page++
        }
        return null
    }

    // =================================================================
    // STEP 4: Play page → kwik URLs
    // =================================================================

    private suspend fun getKwikSources(animeUuid: String, session: String): List<KwikSource> {
        val playUrl = "$BASE_URL/play/$animeUuid/$session"

        val doc = try {
            client.newCall(GET(playUrl, pageHeaders)).awaitSuccess().useAsJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        return doc.select("button[data-src]").mapNotNull { btn ->
            val src = btn.attr("data-src")
            val resolution = btn.attr("data-resolution")
            val audio = btn.attr("data-audio")
            val fansub = btn.attr("data-fansub")

            if (src.isNotBlank() && src.contains("kwik")) {
                KwikSource(src, resolution, audio, fansub)
            } else null
        }
    }

    // =================================================================
    // STEP 5: Kwik page → m3u8 URL
    // =================================================================

    private suspend fun extractM3u8FromKwik(kwikUrl: String): String? {
        val pageBody = try {
            client.newCall(GET(kwikUrl, kwikHeaders)).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return null
        }

        // Strategy 1: Direct m3u8 in plain text
        val directMatch = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(pageBody)
        if (directMatch != null) return directMatch.value

        // Strategy 2: Unpack eval(function(p,a,c,k,e,d){...}) then regex
        val unpacked = JsUnpacker.unpackAndCombine(pageBody)
        if (unpacked != null) {
            val unpackedMatch = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(unpacked)
            if (unpackedMatch != null) return unpackedMatch.value

            val varMatch = Regex("""(?:file|source|src|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)
            if (varMatch != null) return varMatch.groupValues[1]
        }

        // Strategy 3: Regex the packed dictionary directly (no leading quote)
        // Pattern in the packed string: m3u8|uwu|$hash|$num|stream|top|uwucdn|vault|https
        val dictMatch = Regex("""m3u8\|uwu\|([a-f0-9]{64})\|(\d+)\|stream\|top\|(uwucdn)\|(vault)\|https""").find(pageBody)
        if (dictMatch != null) {
            val hash = dictMatch.groupValues[1]
            val num = dictMatch.groupValues[2]
            return "https://vault-11.uwucdn.top/stream/$num/$hash/uwu.m3u8"
        }

        // Strategy 4: Broader vault URL pattern in raw HTML
        val vaultMatch = Regex("""https?://vault-\d+\.[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(pageBody)
        if (vaultMatch != null) return vaultMatch.value

        return null
    }

    // =================================================================
    // STEP 6: m3u8 → Videos
    // =================================================================

    private suspend fun extractVideos(m3u8Url: String, source: KwikSource): List<Video> {
        val audioLabel = if (source.audio == "eng") "Dub" else "Sub"
        val quality = "${source.resolution}p"

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://kwik.cx/")
            .set("Origin", "https://kwik.cx")
            .build()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { res -> "$name ${source.fansub} $audioLabel $quality $res" },
            referer = "https://kwik.cx/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        // Step 1: Search
        val searchResult = searchAnime(title) ?: return emptyList()

        // Step 2: Get UUID from anime page HTML
        val uuid = getAnimeUuid(searchResult.id) ?: return emptyList()

        // Step 3: Get episode session
        val session = getEpisodeSession(searchResult.id, meta.epNum) ?: return emptyList()

        // Step 4: Get kwik sources from play page
        val kwikSources = getKwikSources(uuid, session)
        if (kwikSources.isEmpty()) return emptyList()

        // Step 5+6: Extract m3u8 from each kwik source → videos
        val allVideos = mutableListOf<Video>()

        for (source in kwikSources) {
            try {
                val m3u8Url = extractM3u8FromKwik(source.url) ?: continue
                val videos = extractVideos(m3u8Url, source)
                allVideos.addAll(videos)
            } catch (_: Exception) {
                continue
            }
        }

        return allVideos
    }
}
