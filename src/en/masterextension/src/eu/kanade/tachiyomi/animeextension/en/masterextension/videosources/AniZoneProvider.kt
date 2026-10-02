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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * AniZone video source (anizone.to).
 *
 * Updated to handle the new Alpine.js x-data Vidstack player payloads
 * and the updated Livewire 3 protocol for server switching.
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
        private val SET_VIDEO_REGEX = Regex("""setVideo\(['"]?(\d+)['"]?\)""")
        private val VIDSTACK_REGEX = Regex("""JSON\.parse\('((?:[^'\\]|\\.)*)'\)""")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private var token: String = ""
    private val snapShots: MutableMap<String, String> = mutableMapOf(
        "video_snapshot_key" to "",
    )

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    class LivewireDto(val components: List<ComponentDto>) {
        @Serializable
        class ComponentDto(val snapshot: String, val effects: EffectsDto) {
            @Serializable
            class EffectsDto(val html: String)
        }
    }

    @Serializable
    class LivewireCall(val path: String = "", val method: String, val params: List<JsonElement>)

    @Serializable
    class LivewirePayload(
        @SerialName("_token") val token: String,
        val components: List<LivewireComponentPayload>,
    )

    @Serializable
    class LivewireComponentPayload(
        val snapshot: String,
        val updates: JsonObject,
        val calls: List<LivewireCall>,
    )

    @Serializable
    class VidstackConfig(val src: String, val subtitles: List<VidstackSubtitle> = emptyList())

    @Serializable
    class VidstackSubtitle(val title: String, val file: String)

    // =================================================================
    // LIVEWIRE STATE MANAGEMENT
    // =================================================================

    private fun Document.getSnapshot(): String? = this.selectFirst("main > div[wire:snapshot], main > ul[wire:snapshot], div[wire:snapshot]")
        ?.attr("wire:snapshot")
        ?.replace("&quot;", "\"")

    private fun Document.updateState(): Document {
        this.selectFirst("script[data-csrf]")?.attr("data-csrf")?.takeIf(String::isNotEmpty)?.let { token = it }
        val snapshot = this.getSnapshot()
        snapshot?.let { snapShots["video_snapshot_key"] = it }
        return this
    }

    private fun createLivewireReq(calls: List<LivewireCall>, initialSlug: String): okhttp3.Request {
        if (snapShots["video_snapshot_key"].isNullOrEmpty() || token.isEmpty()) {
            client.newCall(GET(baseUrl + initialSlug, headers)).execute().use { res ->
                Jsoup.parse(res.body.string(), BASE).updateState()
            }
        }

        val requestHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/json")
            .add("X-Livewire", "")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl$initialSlug")
            .build()

        val payload = LivewirePayload(
            token = token,
            components = listOf(
                LivewireComponentPayload(
                    snapshot = snapShots["video_snapshot_key"] ?: "",
                    updates = buildJsonObject { },
                    calls = calls,
                ),
            ),
        )

        return POST(
            url = "$baseUrl/livewire/update",
            headers = requestHeaders,
            body = payload.toJsonRequestBody(),
        )
    }

    // =================================================================
    // SEARCH & EPISODE FINDING
    // =================================================================

    private fun searchAnime(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/anime?search=$encodedTitle&sort=title-asc"

        val doc = client.newCall(GET(url, headers)).execute().use { Jsoup.parse(it.body.string(), BASE) }

        val links = doc.select("a[href*=/anime/]")
            .filter { link ->
                val href = link.attr("href")
                val path = href.substringAfter("/anime/").trim('/')
                path.isNotEmpty() && !path.contains("/")
            }

        val match = links.firstOrNull { link ->
            val text = link.text().trim()
            text.equals(title, ignoreCase = true) ||
                text.contains(title, ignoreCase = true) ||
                title.contains(text, ignoreCase = true)
        } ?: links.firstOrNull()

        val href = match?.attr("abs:href") ?: match?.attr("href") ?: return null
        return href.removePrefix(BASE).substringBefore("?")
    }

    private fun findEpisodeUrl(animeSlug: String, epNum: Int): String? {
        val doc = client.newCall(GET("$BASE$animeSlug", headers)).execute().use { Jsoup.parse(it.body.string(), BASE) }

        val episodes = doc.select("ul > li, div.grid > div").filter { el ->
            el.selectFirst("a[href]") != null
        }

        val match = episodes.firstOrNull { el ->
            val text = el.text()
            val nums = EP_NUM_REGEX.findAll(text).map { it.value }.toList()
            nums.any { it.toFloatOrNull()?.toInt() == epNum }
        }

        val episodeLink = match?.selectFirst("a[href]")
            ?: episodes.getOrNull(epNum - 1)?.selectFirst("a[href]")

        val href = episodeLink?.attr("abs:href") ?: episodeLink?.attr("href") ?: return null
        return href.removePrefix(BASE).substringBefore("?")
    }

    // =================================================================
    // VIDEO EXTRACTION (VIDSTACK & LIVEWIRE)
    // =================================================================

    private fun extractVideosFromEpisodePage(episodePath: String): List<Video> {
        val response = client.newCall(GET("$BASE$episodePath", headers)).execute()
        val initialDocument = Jsoup.parse(response.body.string(), BASE).updateState()
        
        val serverButtons = initialDocument.select("button[wire:click]")
            .filter { it.attr("wire:click").contains("setVideo") }

        if (serverButtons.isEmpty()) {
            return extractVideosFromDocument(initialDocument, "Default")
        }

        val videos = mutableListOf<Video>()

        serverButtons.forEach { btn ->
            val wireClick = btn.attr("wire:click")
            val matchResult = SET_VIDEO_REGEX.find(wireClick)
            val videoId = matchResult?.groupValues?.getOrNull(1) ?: "0"
            val isDefault = btn.hasAttr("disabled")
            val hosterName = btn.selectFirst("div.text-lg")?.text()?.takeIf { it.isNotEmpty() } ?: btn.text().ifBlank { "Server $videoId" }

            try {
                val document = if (isDefault) {
                    initialDocument
                } else {
                    val calls = listOf(
                        LivewireCall(method = "setVideo", params = listOf(JsonPrimitive(videoId.toIntOrNull() ?: 0))),
                    )
                    val req = createLivewireReq(calls, episodePath)
                    val resp = client.newCall(req).execute()
                    
                    if (resp.code == 419) {
                        resp.close()
                        token = ""
                        snapShots["video_snapshot_key"] = ""
                        val retryReq = createLivewireReq(calls, episodePath)
                        val retryResp = client.newCall(retryReq).execute()
                        parseLivewireHtml(retryResp)
                    } else {
                        parseLivewireHtml(resp)
                    }
                }
                
                videos.addAll(extractVideosFromDocument(document, hosterName))
            } catch (_: Exception) {
                // Skip failed servers
            }
        }

        return videos
    }

    private fun parseLivewireHtml(response: Response): Document {
        val dto = response.use { it.body.string() }.parseAs<LivewireDto>()
        val comp = dto.components.firstOrNull() ?: return Jsoup.parse("", BASE)
        snapShots["video_snapshot_key"] = comp.snapshot.replace("\\\"", "\"")
        val html = comp.effects.html.replace("\\\"", "\"").replace("\\n", "")
        return Jsoup.parseBodyFragment(html, BASE)
    }

    private fun Document.vidstackData(): VidstackConfig? {
        val xData = selectFirst("[x-data*=vidstackPlayer]")?.attr("x-data") ?: return null
        val jsonString = VIDSTACK_REGEX.find(xData)?.groupValues?.get(1) ?: return null
        val normalizedJson = jsonString
            .replace("""\u0022""", "\"")
            .replace("""\u0026""", "&")
            .replace("""\'""", "'")
            .replace("""\/""", "/")
        return runCatching { normalizedJson.parseAs<VidstackConfig>() }.getOrNull()
    }

    private fun extractVideosFromDocument(document: Document, hosterName: String): List<Video> {
        val vidstack = document.vidstackData()
        
        val subtitles = vidstack?.subtitles?.map { Track(it.file.replace("\\/", "/"), it.title) }
            ?: document.select("track[kind=subtitles]").map { Track(it.attr("src").replace("\\/", "/"), it.attr("label")) }
            
        val videoUrl = vidstack?.src ?: document.selectFirst("media-player")?.attr("src") ?: return emptyList()
        
        return playlistUtils.extractFromHls(
            playlistUrl = videoUrl,
            referer = "$BASE/",
            videoNameGen = { q -> "$name $hosterName - $q" },
            subtitleList = subtitles,
        )
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
                token = ""
                snapShots["video_snapshot_key"] = ""

                val animeSlug = searchAnime(title) ?: return@withContext emptyList<Video>()
                val episodePath = findEpisodeUrl(animeSlug, meta.epNum) ?: return@withContext emptyList<Video>()
                extractVideosFromEpisodePage(episodePath)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
