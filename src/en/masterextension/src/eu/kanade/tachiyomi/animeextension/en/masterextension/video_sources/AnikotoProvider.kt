package eu.kanade.tachiyomi.animeextension.en.masterextension.video_sources

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
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Anikoto / HiAnime-clone video source.
 *
 * Flow:
 *   1. Search site by title → anime slug
 *   2. Load episode watch page → server button IDs
 *   3. AJAX /ajax/server?get=$id → embed URL
 *   4. Extract m3u8 from embed page (multiple strategies)
 *   5. PlaylistUtils → quality variants
 *
 * Self-contained: all DTOs, helpers, and extraction logic live in this file.
 */
class AnikotoProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "Anikoto"

    // =================================================================
    // CONFIG
    // =================================================================

    companion object {
        private val DOMAINS = listOf(
            "https://anikototv.to",
            "https://anikoto.bz",
            "https://anikoto.cz",
            "https://anikoto.me",
            "https://anikoto.net",
        )
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // =================================================================
    // LOCAL DTOs (private to this provider)
    // =================================================================

    @Serializable
    private data class AjaxServerResponse(
        val result: AjaxServerResult
    )

    @Serializable
    private data class AjaxServerResult(
        val url: String
    )

    @Serializable
    private data class SourceResponseDto(
        @Serializable(with = SourcesSerializer::class)
        val sources: String,
        val tracks: List<TrackDto>? = null
    )

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String = ""
    )

    private object SourcesSerializer : KSerializer<String> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun deserialize(decoder: Decoder): String {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            return when (element) {
                is JsonObject -> element["file"]?.jsonPrimitive?.content
                is JsonArray -> element.firstOrNull()?.let {
                    when (it) {
                        is JsonObject -> it["file"]?.jsonPrimitive?.content
                        is JsonPrimitive -> it.content
                        else -> null
                    }
                }
                is JsonPrimitive -> element.content
                else -> null
            } ?: throw IllegalStateException("No valid m3u8 in sources")
        }

        override fun serialize(encoder: Encoder, value: String) =
            throw UnsupportedOperationException()
    }

    private data class ServerData(
        val type: String,
        val serverId: String,
        val serverName: String,
        val epUrl: String
    )

    // =================================================================
    // HEADERS
    // =================================================================

    private fun siteHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private fun ajaxHeaders(baseUrl: String, epPath: String) = headers.newBuilder()
        .set("Accept", "application/json, text/javascript, */*; q=0.01")
        .set("Referer", "$baseUrl$epPath")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    // =================================================================
    // STEP 1: Search
    // =================================================================

    private suspend fun searchAnime(title: String): Pair<String, String>? {
        for (domain in DOMAINS) {
            try {
                val url = "$domain/search".toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", title)
                    .build().toString()

                val html = client.newCall(GET(url, siteHeaders(domain)))
                    .awaitSuccess().bodyString()

                val doc = Jsoup.parse(html)
                val result = doc.selectFirst("div.item a[href*=/watch/]")
                    ?: doc.selectFirst("a[href*=/watch/]")

                if (result != null) {
                    val href = result.attr("href")
                    if (href.isNotBlank()) return domain to href
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Episode page → server IDs
    // =================================================================

    private suspend fun getServerIds(
        baseUrl: String,
        animePath: String,
        epNum: Int
    ): List<ServerData> {
        val slug = animePath.substringAfter("/watch/").substringBefore("/")
        val epUrl = "/watch/$slug/ep-$epNum"

        val html = client.newCall(GET("$baseUrl$epUrl", siteHeaders(baseUrl)))
            .awaitSuccess().bodyString()

        val doc = Jsoup.parse(html)
        val servers = mutableListOf<ServerData>()

        doc.select("div.type").forEach { typeElem ->
            val typeLabel = typeElem.selectFirst("label")?.text()?.trim()
                ?: typeElem.attr("data-type").trim().ifBlank { "Sub" }

            typeElem.select("li[data-link-id]").forEach { li ->
                val id = li.attr("data-link-id")
                val serverName = li.text().trim()
                if (id.isNotBlank() && serverName.isNotBlank()) {
                    servers.add(ServerData(typeLabel, id, serverName, epUrl))
                }
            }

            if (servers.isEmpty()) {
                typeElem.select("a.server[data-link-id]").forEach { a ->
                    val id = a.attr("data-link-id")
                    val serverName = a.selectFirst("span")?.text()?.trim()
                        ?: a.text().trim()
                    if (id.isNotBlank() && serverName.isNotBlank()) {
                        servers.add(ServerData(typeLabel, id, serverName, epUrl))
                    }
                }
            }
        }

        return servers
    }

    // =================================================================
    // STEP 3: AJAX → embed URL
    // =================================================================

    private suspend fun getEmbedUrl(
        baseUrl: String,
        serverId: String,
        epUrl: String
    ): String {
        return client.newCall(
            GET("$baseUrl/ajax/server?get=$serverId", ajaxHeaders(baseUrl, epUrl))
        ).awaitSuccess().parseAs<AjaxServerResponse>().result.url
    }

    // =================================================================
    // STEP 4: Extract m3u8 from embed page
    // =================================================================

    private suspend fun extractVideosFromEmbed(
        embedUrl: String,
        server: ServerData
    ): List<Video> {
        // Direct m3u8
        if (embedUrl.contains(".m3u8")) {
            return extractHls(embedUrl, server, embedUrl.substringBeforeLast("/"))
        }

        val host = embedUrl.toHttpUrl().host
        val playerHeaders = headers.newBuilder()
            .set("Referer", "${DOMAINS.first()}/")
            .build()

        val pageBody = client.newCall(GET(embedUrl, playerHeaders))
            .awaitSuccess().bodyString()

        // Strategy A: data-id → /stream/getSources
        val dataId = Regex("""data-id="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchFromGetSources(dataId, host, embedUrl, server)
        }

        // Strategy B: iframe → follow
        val iframeSrc = Regex("""<iframe[^>]+src="([^"]+)"""").find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            return extractVideosFromEmbed(resolveUrl(iframeSrc, embedUrl), server)
        }

        // Strategy C: m3u8 in HTML
        val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageBody)
        if (m3u8Match != null) {
            return extractHls(m3u8Match.value, server, "https://$host/")
        }

        // Strategy D: <source> tag
        val sourceSrc = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""").find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            return extractHls(resolveUrl(sourceSrc, embedUrl), server, "https://$host/")
        }

        // Strategy E: JS variable
        val jsVar = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*\.m3u8[^"']*)["']""" +
            """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']"""
        ).find(pageBody)
        if (jsVar != null) {
            val url = jsVar.groupValues[1].ifBlank { jsVar.groupValues[2] }
            if (url.isNotBlank()) {
                return extractHls(resolveUrl(url, embedUrl), server, "https://$host/")
            }
        }

        return emptyList()
    }

    // =================================================================
    // /stream/getSources → m3u8 + subtitles
    // =================================================================

    private suspend fun fetchFromGetSources(
        dataId: String,
        host: String,
        embedUrl: String,
        server: ServerData
    ): List<Video> {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .set("Origin", "https://$host")
            .build()

        val sourceData = try {
            client.newCall(
                GET("https://$host/stream/getSources?id=$dataId", apiHeaders)
            ).awaitSuccess().parseAs<SourceResponseDto>()
        } catch (_: Exception) {
            client.newCall(
                GET("https://$host/stream/getSourcesNew?id=$dataId", apiHeaders)
            ).awaitSuccess().parseAs<SourceResponseDto>()
        }

        val m3u8 = sourceData.sources
        if (!m3u8.startsWith("http")) return emptyList()

        val subtitles = sourceData.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        return playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality -> "${server.serverName} - ${server.type} - $quality" },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // Simple HLS (no subtitles)
    // =================================================================

    private suspend fun extractHls(
        m3u8Url: String,
        server: ServerData,
        referer: String
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", referer)
            .build()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "${server.serverName} - ${server.type} - $quality" },
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // UTILS
    // =================================================================

    private fun resolveUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.toHttpUrl().resolve(url)?.toString() ?: url
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val (baseUrl, animePath) = searchAnime(title) ?: return emptyList()
        val servers = getServerIds(baseUrl, animePath, meta.epNum)
        if (servers.isEmpty()) return emptyList()

        return servers.parallelCatchingFlatMap { server ->
            val embedUrl = getEmbedUrl(baseUrl, server.serverId, server.epUrl)
            extractVideosFromEmbed(embedUrl, server)
        }
    }
}
