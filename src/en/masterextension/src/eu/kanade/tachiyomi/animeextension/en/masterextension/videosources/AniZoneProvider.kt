package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup.parseBodyFragment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

/**
 * AniZone video source (anizone.to).
 *
 * Laravel Livewire application. Video URLs are direct .m3u8 links inside
 * <media-player src="..."> HTML attributes, but reaching them requires
 * speaking the Livewire protocol (CSRF token + snapshot + POST /livewire/update).
 *
 * Flow:
 *   1. GET /anime?search={title} → find anime slug
 *   2. GET /anime/{slug} → find episode link
 *   3. GET /anime/{slug}/episode/{ep} → extract media-player src + servers
 *   4. POST /livewire/update (setVideo) → alternate server m3u8 URLs
 *   5. PlaylistUtils.extractFromHls → quality variants
 *
 * No encryption, no JS execution, no WASM. Just HTML parsing + Livewire protocol.
 */
class AniZoneProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniZone"
    override val baseUrl = "https://anizone.to"

    companion object {
        private const val BASE = "https://anizone.to"
        private val EP_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val SET_VIDEO_REGEX = Regex("""setVideo\('(\d+)'\)""")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ── Livewire state (per-fetch, not shared across calls) ──
    private var csrfToken = ""
    private var currentSnapshot = ""

    // =================================================================
    // DTOs (private to this provider)
    // =================================================================

    @Serializable
    private data class LivewireResponseDto(
        val components: List<LivewireComponentDto> = emptyList(),
    )

    @Serializable
    private data class LivewireComponentDto(
        val snapshot: String = "",
        val effects: LivewireEffectsDto? = null,
    )

    @Serializable
    private data class LivewireEffectsDto(
        val html: String = "",
    )

    @Serializable
    private data class LivewireRequestDto(
        @SerialName("_token") val token: String,
        val components: List<LivewireComponentRequestDto>,
    )

    @Serializable
    private data class LivewireComponentRequestDto(
        val calls: JsonArray,
        val snapshot: String,
        val updates: JsonObject,
    )

    // =================================================================
    // HEADERS
    // =================================================================

    private fun siteHeaders(referer: String = "$BASE/") = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private fun livewireHeaders(referer: String) = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept", "application/json, text/javascript, */*; q=0.01")
        .set("X-Livewire", "")
        .set("X-CSRF-TOKEN", csrfToken)
        .set("Origin", BASE)
        .set("Content-Type", "application/json")
        .build()

    // =================================================================
    // LIVEWIRE STATE MANAGEMENT
    // =================================================================

    /** Extract CSRF token + snapshot from a full-page HTML document */
    private fun Document.extractState(): Document {
        selectFirst("script[data-csrf]")?.attr("data-csrf")
            ?.takeIf { it.isNotEmpty() }
            ?.let { csrfToken = it }

        selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot]")
            ?.attr("wire:snapshot")
            ?.replace("&quot;", "\"")
            ?.let { currentSnapshot = it }

        return this
    }

    /** Build and execute a Livewire POST request with 419 retry */
    private fun livewireCall(
        method: String,
        params: JsonArray = buildJsonArray {},
        refererPath: String,
    ): Document {
        val referer = "$BASE$refererPath"

        // Ensure we have valid state
        if (csrfToken.isEmpty() || currentSnapshot.isEmpty()) {
            refreshState(refererPath)
        }

        val body = LivewireRequestDto(
            token = csrfToken,
            components = listOf(
                LivewireComponentRequestDto(
                    calls = buildJsonArray {
                        addJsonObject {
                            put("path", "")
                            put("method", method)
                            put("params", params)
                        }
                    },
                    snapshot = currentSnapshot,
                    updates = buildJsonObject {},
                ),
            ),
        ).toJsonRequestBody()

        var response = client.newCall(
            POST("$BASE/livewire/update", livewireHeaders(referer), body),
        ).execute()

        // 419 = CSRF token expired → refresh and retry once
        if (response.code == 419) {
            response.close()
            csrfToken = ""
            currentSnapshot = ""
            refreshState(refererPath)

            val retryBody = LivewireRequestDto(
                token = csrfToken,
                components = listOf(
                    LivewireComponentRequestDto(
                        calls = buildJsonArray {
                            addJsonObject {
                                put("path", "")
                                put("method", method)
                                put("params", params)
                            }
                        },
                        snapshot = currentSnapshot,
                        updates = buildJsonObject {},
                    ),
                ),
            ).toJsonRequestBody()

            response = client.newCall(
                POST("$BASE/livewire/update", livewireHeaders(referer), retryBody),
            ).execute()
        }

        return parseLivewireResponse(response)
    }

    /** Parse Livewire JSON response → Jsoup Document of the HTML fragment */
    private fun parseLivewireResponse(response: Response): Document {
        val dto = response.use { it.body.string() }.parseAs<LivewireResponseDto>()
        val comp = dto.components.firstOrNull() ?: return parseBodyFragment("", BASE)

        currentSnapshot = comp.snapshot.replace("\\\"", "\"")

        val html = comp.effects?.html
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "")
            ?: ""

        return parseBodyFragment(html, BASE)
    }

    /** Reload a page to get fresh CSRF token + snapshot */
    private fun refreshState(path: String) {
        val doc = client.newCall(
            GET("$BASE$path", siteHeaders("$BASE$path")),
        ).execute().use { parseBodyFragment(it.body.string(), BASE) }

        // For full pages, use Jsoup parse
        val fullDoc = org.jsoup.Jsoup.parse(
            client.newCall(GET("$BASE$path", siteHeaders("$BASE$path")))
                .execute().use { it.body.string() },
            BASE,
        )
        fullDoc.extractState()
    }

    // =================================================================
    // STEP 1: Search for anime by title
    // =================================================================

    private fun searchAnime(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/anime?search=$encodedTitle&sort=title-asc"

        val doc = client.newCall(GET(url, siteHeaders()))
            .execute().use { org.jsoup.Jsoup.parse(it.body.string(), BASE) }

        doc.extractState()

        // Find matching anime link
        val links = doc.select("a[href*=/anime/]")
            .filter { link ->
                val href = link.attr("href")
                val path = href.substringAfter("/anime/").trim('/')
                path.isNotEmpty() && !path.contains("/")
            }

        // Try exact/contains match first
        val match = links.firstOrNull { link ->
            val text = link.text().trim()
            text.equals(title, ignoreCase = true) ||
                text.contains(title, ignoreCase = true) ||
                title.contains(text, ignoreCase = true)
        } ?: links.firstOrNull()

        val href = match?.attr("abs:href") ?: match?.attr("href") ?: return null
        return href.removePrefix(BASE).substringBefore("?")
    }

    // =================================================================
    // STEP 2: Find episode URL on anime page
    // =================================================================

    private fun findEpisodeUrl(animeSlug: String, epNum: Int): String? {
        val doc = client.newCall(GET("$BASE$animeSlug", siteHeaders("$BASE$animeSlug")))
            .execute().use { org.jsoup.Jsoup.parse(it.body.string(), BASE) }

        doc.extractState()

        val episodes = doc.select("ul > li").filter { li ->
            li.selectFirst("a[href*=/anime/]") != null
        }

        // Match by episode number
        val match = episodes.firstOrNull { li ->
            val h3Text = li.selectFirst("h3")?.text() ?: ""
            val nums = EP_NUM_REGEX.findAll(h3Text).map { it.value }.toList()
            nums.any { it.toFloatOrNull()?.toInt() == epNum }
        }

        // Fallback: try index-based (episodes may be in order)
        val episodeLink = match?.selectFirst("a[href*=/anime/]")
            ?: episodes.getOrNull(epNum - 1)?.selectFirst("a[href*=/anime/]")

        val href = episodeLink?.attr("abs:href") ?: episodeLink?.attr("href") ?: return null
        return href.removePrefix(BASE).substringBefore("?")
    }

    // =================================================================
    // STEP 3: Extract videos from episode page
    // =================================================================

    private fun extractVideosFromEpisodePage(episodePath: String): List<Video> {
        val doc = client.newCall(GET("$BASE$episodePath", siteHeaders("$BASE$episodePath")))
            .execute().use { org.jsoup.Jsoup.parse(it.body.string(), BASE) }

        doc.extractState()

        val videos = mutableListOf<Video>()

        // Get server buttons
        val serverButtons = doc.select("button[wire:click]")
            .filter { it.attr("wire:click").contains("setVideo") }

        // Default server (first one, already in the page)
        val defaultM3u8 = doc.selectFirst("media-player")?.attr("src")
        val defaultSubs = doc.select("track[kind=subtitles]").map {
            Track(it.attr("src"), it.attr("label"))
        }
        val defaultName = serverButtons.firstOrNull()?.text()?.trim() ?: "Default"

        if (!defaultM3u8.isNullOrBlank()) {
            videos.addAll(
                playlistUtils.extractFromHls(
                    playlistUrl = defaultM3u8,
                    referer = "$BASE/",
                    videoNameGen = { quality -> "$name $defaultName $quality" },
                    subtitleList = defaultSubs,
                ),
            )
        }

        // Alternate servers via Livewire setVideo calls
        val videoSnapshot = currentSnapshot
        serverButtons.drop(1).forEach { button ->
            try {
                val matchResult = SET_VIDEO_REGEX.find(button.attr("wire:click"))
                val videoId = matchResult?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach

                currentSnapshot = videoSnapshot

                val params = buildJsonArray { add(videoId) }
                val fragment = livewireCall("setVideo", params, episodePath)

                val m3u8 = fragment.selectFirst("media-player")?.attr("src")
                val subs = fragment.select("track[kind=subtitles]").map {
                    Track(it.attr("src"), it.attr("label"))
                }
                val serverName = button.text().trim().ifBlank { "Server $videoId" }

                if (!m3u8.isNullOrBlank()) {
                    videos.addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = m3u8,
                            referer = "$BASE/",
                            videoNameGen = { quality -> "$name $serverName $quality" },
                            subtitleList = subs,
                        ),
                    )
                }
            } catch (_: Exception) {
                // Skip failed servers
            }
        }

        return videos
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                // Reset Livewire state for each fetch
                csrfToken = ""
                currentSnapshot = ""

                // Step 1: Search
                val animeSlug = searchAnime(title) ?: return@withContext emptyList<Video>()

                // Step 2: Find episode
                val episodePath = findEpisodeUrl(animeSlug, meta.epNum)
                    ?: return@withContext emptyList<Video>()

                // Step 3: Extract videos
                extractVideosFromEpisodePage(episodePath)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
