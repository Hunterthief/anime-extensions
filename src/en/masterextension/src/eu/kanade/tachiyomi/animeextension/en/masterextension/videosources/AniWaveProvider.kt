package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniWaveProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniWave"

    companion object {
        // FIX: Add fallback domain (matches reference AniWave extension)
        private val DOMAINS = listOf("https://animewave.to", "https://aniwave.cz")

        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
        private val JS_VAR_M3U8_REGEX = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*\.m3u8[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*\.m3u8[^"']*)["']""",
        )
        private val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")

        // VRF keys — verified identical to reference AnikotoUtils
        private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
        private const val RC4_KEY_1 = "ItFKjuWokn4ZpB"
        private const val RC4_KEY_2 = "fOyt97QWFB3"
        private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
        private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
        private const val RC4_KEY_3 = "736y1uTJpBLUX"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun docHeaders(baseUrl: String) = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private fun xhrHeaders(baseUrl: String, referer: String): Headers {
        return headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("Referer", referer)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // =================================================================
    // VRF Encryption (matches reference AnikotoUtils exactly)
    // =================================================================

    private fun vrfEncrypt(input: String): String {
        var vrf = input
        vrf = exchange(vrf, EXCHANGE_KEY_1[0], EXCHANGE_KEY_1[1])
        vrf = rc4Encrypt(RC4_KEY_1, vrf)
        vrf = rc4Encrypt(RC4_KEY_2, vrf)
        vrf = exchange(vrf, EXCHANGE_KEY_2[0], EXCHANGE_KEY_2[1])
        vrf = exchange(vrf, EXCHANGE_KEY_3[0], EXCHANGE_KEY_3[1])
        vrf = vrf.reversed()
        vrf = rc4Encrypt(RC4_KEY_3, vrf)
        vrf = Base64.encode(vrf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
        return java.net.URLEncoder.encode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.parameters)
        return Base64.encode(cipher.doFinal(input.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, key1: String, key2: String): String {
        return input.map { ch ->
            val idx = key1.indexOf(ch)
            if (idx != -1) key2[idx] else ch
        }.joinToString("")
    }

    // =================================================================
    // STEP 1: Search
    // =================================================================

    private suspend fun searchAnime(title: String): Pair<String, String>? {
        val cleanTitle = title
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .trim()

        val encodedTitle = java.net.URLEncoder.encode(cleanTitle, "utf-8")

        for (domain in DOMAINS) {
            // Try with VRF first, then without
            val vrf = runCatching { vrfEncrypt(cleanTitle) }.getOrNull() ?: ""
            val urls = listOf(
                "$domain/filter?keyword=$encodedTitle&vrf=$vrf",
                "$domain/filter?keyword=$encodedTitle",
            )

            for (url in urls) {
                try {
                    val doc = client.newCall(GET(url, docHeaders(domain)))
                        .awaitSuccess().asJsoup()

                    // FIX: Match reference selectors more closely
                    val firstResult = doc.selectFirst("div.ani.items > div.item a.name")
                        ?: doc.selectFirst("div.item a[href*=/watch/]")
                        ?: doc.selectFirst("a[href*=/watch/]")

                    val href = firstResult?.attr("href") ?: continue
                    val path = EP_URL_SUFFIX_REGEX.replace(href.substringBefore("?"), "")
                    if (path.isNotBlank()) return domain to path
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return null
    }

    // =================================================================
    // STEP 2: Anime page → data-id
    // =================================================================

    private suspend fun getAnimeId(baseUrl: String, animePath: String): String? {
        return try {
            val doc = client.newCall(GET("$baseUrl$animePath", docHeaders(baseUrl)))
                .awaitSuccess().asJsoup()
            doc.selectFirst("[data-id]")?.attr("data-id")
                ?: doc.selectFirst("[data-tip]")?.attr("data-tip")
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 3: Episode list → episode data-ids
    // =================================================================

    private suspend fun getEpisodeIds(
        baseUrl: String,
        animeId: String,
        epNum: Int,
        animePath: String,
    ): String? {
        val vrf = runCatching { vrfEncrypt(animeId) }.getOrNull() ?: ""
        val url = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf"
        return try {
            val responseBody = client.newCall(
                GET(url, xhrHeaders(baseUrl, "$baseUrl$animePath")),
            ).awaitSuccess().bodyString()

            val result = responseBody.parseAs<ResultResponse>()
            val doc = Jsoup.parseBodyFragment(result.result)

            // Match reference: div.episodes ul > li > a with data-num
            val epElement = doc.select("div.episodes ul > li > a").firstOrNull {
                it.attr("data-num") == epNum.toString()
            } ?: doc.select("div.episodes ul > li > a").firstOrNull {
                it.attr("data-num").toFloatOrNull()?.toInt() == epNum
            }

            epElement?.attr("data-ids")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 4: Server list
    // =================================================================

    private data class ServerInfo(val id: String, val name: String, val type: String)

    private suspend fun getServerList(
        baseUrl: String,
        episodeIds: String,
        epUrl: String,
    ): List<ServerInfo> {
        val url = "$baseUrl/ajax/server/list?servers=$episodeIds"
        return try {
            val responseBody = client.newCall(
                GET(url, xhrHeaders(baseUrl, "$baseUrl$epUrl")),
            ).awaitSuccess().bodyString()

            val result = responseBody.parseAs<ResultResponse>()
            val doc = Jsoup.parseBodyFragment(result.result)

            // Match reference: div.servers > div.type → li[data-link-id]
            doc.select("div.servers > div.type").flatMap { typeElem ->
                val label = typeElem.selectFirst("label")?.text()?.trim()
                    ?: typeElem.attr("data-type").ifEmpty { "Sub" }

                typeElem.select("li").mapNotNull { li ->
                    if (li.hasClass("download-icon")) return@mapNotNull null
                    val serverId = li.attr("data-link-id")
                    val serverName = li.text().trim()
                    if (serverId.isNotEmpty() && serverName.isNotEmpty()) {
                        ServerInfo(serverId, serverName, label)
                    } else null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // STEP 5: Embed URL
    // =================================================================

    private suspend fun getEmbedUrl(baseUrl: String, serverId: String, epUrl: String): String? {
        return try {
            val responseBody = client.newCall(
                GET("$baseUrl/ajax/server?get=$serverId", xhrHeaders(baseUrl, "$baseUrl$epUrl")),
            ).awaitSuccess().bodyString()
            responseBody.parseAs<ServerResponseDto>().result.url
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 6: Extract m3u8 (matches reference AnikotoExtractor strategies)
    // =================================================================

    private suspend fun extractFromEmbed(embedUrl: String, server: ServerInfo): List<Video> {
        // Direct m3u8 in the embed URL itself
        if (embedUrl.endsWith(".m3u8") || (embedUrl.contains(".m3u8") && !embedUrl.contains("/stream/"))) {
            return extractHls(embedUrl, server, embedUrl.substringBeforeLast("/"))
        }

        val host = try { embedUrl.toHttpUrl().host } catch (_: Exception) { return emptyList() }

        val pageBody = try {
            client.newCall(GET(embedUrl, docHeaders("https://$host")))
                .awaitSuccess().bodyString()
        } catch (_: Exception) {
            return emptyList()
        }

        // Strategy 1: data-id → getSources API (matches reference)
        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            val videos = fetchSourcesFromApi(dataId, host, embedUrl, server)
            if (videos.isNotEmpty()) return videos
        }

        // Strategy 2: iframe → follow one level (matches reference)
        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolved = resolveUrl(iframeSrc, embedUrl)
            val iframeHost = try { resolved.toHttpUrl().host } catch (_: Exception) { return emptyList() }
            val iframeBody = try {
                client.newCall(GET(resolved, docHeaders("https://$iframeHost")))
                    .awaitSuccess().bodyString()
            } catch (_: Exception) {
                return emptyList()
            }
            val iframeDataId = DATA_ID_REGEX.find(iframeBody)?.groupValues?.get(1)
            if (iframeDataId != null) {
                val videos = fetchSourcesFromApi(iframeDataId, iframeHost, resolved, server)
                if (videos.isNotEmpty()) return videos
            }
            val iframeM3u8 = M3U8_REGEX.find(iframeBody)?.value
            if (iframeM3u8 != null) return extractHls(iframeM3u8, server, "https://$iframeHost/")
        }

        // Strategy 3: direct m3u8 in page HTML
        val m3u8Url = M3U8_REGEX.find(pageBody)?.value
        if (m3u8Url != null) return extractHls(m3u8Url, server, "https://$host/")

        // Strategy 4: <source> tag
        val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) return extractHls(resolveUrl(sourceSrc, embedUrl), server, "https://$host/")

        // Strategy 5: JS variable
        val jsVar = JS_VAR_M3U8_REGEX.find(pageBody)
        if (jsVar != null) {
            val url = jsVar.groupValues[1].ifBlank { jsVar.groupValues[2] }
            if (url.isNotBlank()) return extractHls(resolveUrl(url, embedUrl), server, "https://$host/")
        }

        return emptyList()
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        server: ServerInfo,
    ): List<Video> {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .set("Origin", "https://$host")
            .build()

        // FIX: Match reference — try getSources then getSourcesNew, both with ?id=X&id=X
        val data = try {
            client.newCall(
                GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString().parseAs<SourceResponseDto>()
        } catch (_: Exception) {
            try {
                client.newCall(
                    GET("https://$host/stream/getSourcesNew?id=$dataId&id=$dataId", apiHeaders),
                ).awaitSuccess().bodyString().parseAs<SourceResponseDto>()
            } catch (_: Exception) {
                return emptyList()
            }
        }

        val m3u8 = data.sources.takeIf { it.startsWith("http") } ?: return emptyList()

        val subtitles = data.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        return extractHls(m3u8, server, "https://$host/", subtitles)
    }

    private fun extractHls(
        m3u8Url: String,
        server: ServerInfo,
        referer: String,
        subtitles: List<Track> = emptyList(),
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", referer.removeSuffix("/"))
            .build()

        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "${server.name}$typeSuffix - $quality" },
            subtitleList = subtitles,
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    private fun resolveUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> try { base.toHttpUrl().resolve(url)?.toString() ?: url } catch (_: Exception) { url }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val epNum = if (meta.epNum > 0) meta.epNum else 1

        return try {
            val (baseUrl, animePath) = searchAnime(title) ?: return emptyList()
            val animeId = getAnimeId(baseUrl, animePath) ?: return emptyList()
            val episodeIds = getEpisodeIds(baseUrl, animeId, epNum, animePath) ?: return emptyList()

            val epUrl = "$animePath/ep-$epNum"
            val servers = getServerList(baseUrl, episodeIds, epUrl)
            if (servers.isEmpty()) return emptyList()

            servers.flatMap { server ->
                runCatching {
                    val embedUrl = getEmbedUrl(baseUrl, server.id, epUrl) ?: return@flatMap emptyList()
                    extractFromEmbed(embedUrl, server)
                }.getOrElse { emptyList() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class ResultResponse(val result: String)

    @Serializable
    private data class ServerResponseDto(val result: ServerResultDto) {
        @Serializable
        data class ServerResultDto(val url: String)
    }

    @Serializable
    private data class SourceResponseDto(
        @Serializable(with = SourcesSerializer::class) val sources: String,
        val tracks: List<TrackDto>? = null,
    ) {
        @Serializable
        data class TrackDto(val file: String, val kind: String, val label: String = "")
    }

    private object SourcesSerializer : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Sources", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): String {
            val jsonDecoder = decoder as JsonDecoder
            val element = jsonDecoder.decodeJsonElement()
            return when (element) {
                is JsonObject -> element["file"]?.jsonPrimitive?.content ?: ""
                is JsonArray -> element.firstOrNull()?.let {
                    when (it) {
                        is JsonObject -> it["file"]?.jsonPrimitive?.content
                        is JsonPrimitive -> it.content
                        else -> null
                    }
                } ?: ""
                is JsonPrimitive -> element.content
                else -> ""
            }
        }

        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(value)
        }
    }
}
