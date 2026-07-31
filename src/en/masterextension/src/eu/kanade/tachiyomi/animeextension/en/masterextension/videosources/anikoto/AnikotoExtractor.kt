package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anikoto

import android.util.Base64
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

data class AnikotoVideoData(
    val type: String,
    val serverId: String,
    val serverName: String,
)

class AnikotoExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
    private val playlistUtils: PlaylistUtils,
    private val m3u8ServerManager: M3u8ServerManager,
    private val mapperUrl: String = "https://mapper.nekostream.site/api",
) {

    suspend fun extractVideos(
        document: Document,
        episodeIds: String,
        epUrl: String,
        malId: String,
        slug: String,
        ts: String,
    ): List<Video> {
        val serverData = parseServerListData(document).toMutableList()
        serverData.addAll(fetchMapperServers(malId, slug, ts))

        return serverData.parallelCatchingFlatMap { server ->
            extractVideo(server, epUrl)
        }
    }

    // =================================================================
    // Server list parsing
    // =================================================================

    private fun parseServerListData(document: Document): List<AnikotoVideoData> {
        val typeElements = document.select("div.servers > div.type")

        return typeElements.flatMap { elem ->
            val label = resolveTypeLabel(elem)

            elem.select("li").mapNotNull { serverElement ->
                if (serverElement.hasClass("download-icon")) return@mapNotNull null

                val serverId = serverElement.attr("data-link-id")
                if (serverId.isEmpty()) return@mapNotNull null

                val serverName = serverElement.text()

                AnikotoVideoData(label, serverId, serverName)
            }
        }
    }

    private fun resolveTypeLabel(typeElem: org.jsoup.nodes.Element): String {
        val labelText = typeElem.selectFirst("label")?.text().orEmpty()
        val dataType = typeElem.attr("data-type")

        return when (labelText.lowercase()) {
            "sub" -> "Sub"
            "h-sub" -> "H-Sub"
            "hsub" -> "HSub"
            "dub" -> "Dub"
            "a-dub", "adub" -> "A-Dub"
            "s-sub" -> "S-Sub"
            else -> when (dataType.lowercase()) {
                "sub" -> "Sub"
                "hsub" -> "HSub"
                "dub" -> "Dub"
                "adub" -> "A-Dub"
                "" -> if (labelText.isNotEmpty()) labelText.replaceFirstChar { it.uppercase() } else "Unknown"
                else -> dataType.replaceFirstChar { it.uppercase() }
            }
        }
    }

    // =================================================================
    // Mapper API
    // =================================================================

    private suspend fun fetchMapperServers(
        malId: String,
        slug: String,
        ts: String,
    ): List<AnikotoVideoData> {
        if (malId.isEmpty() || slug.isEmpty() || ts.isEmpty()) return emptyList()

        val apiUrl = "$mapperUrl/mal/$malId/$slug/$ts"

        return try {
            val mapperHeaders = headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Referer", "$baseUrl/")
                add("Origin", baseUrl)
            }.build()

            client.newCall(GET(apiUrl, mapperHeaders)).awaitSuccess().use { apiResponse ->
                val mapperJson = apiResponse.parseAs<Map<String, AnikotoMapperServerDto?>>()

                val servers = mutableListOf<AnikotoVideoData>()

                for ((key, serverDto) in mapperJson) {
                    if (key.equals("status", true)) continue
                    val serverName = mapMapperServerName(key)

                    listOf("sub" to "H-Sub", "dub" to "A-Dub").forEach { (typeKey, typeLabel) ->
                        val linkDto = when (typeKey) {
                            "sub" -> serverDto?.sub
                            "dub" -> serverDto?.dub
                            else -> null
                        } ?: return@forEach

                        servers.add(AnikotoVideoData(typeLabel, linkDto.url, serverName))
                    }
                }

                servers
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mapMapperServerName(key: String): String = when {
        key.equals("gogoanime", true) -> "Vidstream"
        key.equals("anivibe", true) -> "Vibe-Stream"
        key.equals("animepahe", true) -> "Kiwi-Stream"
        key.startsWith("Kiwi-Stream", true) -> "Kiwi-Stream"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    // =================================================================
    // Video extraction
    // =================================================================

    private suspend fun extractVideo(server: AnikotoVideoData, epUrl: String): List<Video> = try {
        val embedLink = if (server.serverId.startsWith("http")) {
            server.serverId
        } else {
            getEmbedLink(server.serverId, epUrl)
        }

        val result = when {
            embedLink.contains("mewcdn.online/player/plyr.php") ->
                extractFromMewcdnPlayer(embedLink, server)
            embedLink.endsWith(".m3u8") || (embedLink.contains(".m3u8") && !embedLink.contains("/stream/")) ->
                extractDirectM3u8(embedLink, server)
            else ->
                extractFromPlayer(embedLink, server)
        }

        val needsProxy = result.requiresProxy || alwaysNeedsProxy(server.serverName)

        if (needsProxy) proxyVideoList(result.videos) else result.videos
    } catch (_: Exception) {
        emptyList()
    }

    private fun alwaysNeedsProxy(serverName: String): Boolean {
        val name = serverName.lowercase()
        return name.contains("kiwi") || name.contains("vidplay")
    }

    private suspend fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return client.newCall(GET("$baseUrl/ajax/server?get=$serverId", listHeaders))
            .awaitSuccess().use { response ->
                response.parseAs<AnikotoServerResponseDto>().result.url
            }
    }

    // =================================================================
    // Extraction strategies
    // =================================================================

    private data class ExtractionResult(
        val videos: List<Video>,
        val requiresProxy: Boolean,
    )

    private suspend fun extractFromPlayer(
        embedUrl: String,
        server: AnikotoVideoData,
        pageReferer: String = "$baseUrl/",
    ): ExtractionResult {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            return ExtractionResult(emptyList(), false)
        }

        val pageHeaders = headers.newBuilder()
            .add("Referer", pageReferer)
            .build()

        val pageBody = client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use {
            it.body.string()
        }

        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, server)
        }

        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = resolveUrl(iframeSrc, embedUrl)
            return extractFromPlayer(resolvedSrc, server, pageReferer = embedUrl)
        }

        val directM3u8 = M3U8_REGEX.find(pageBody)?.groupValues?.get(0)
        if (directM3u8 != null) {
            return extractDirectM3u8(directM3u8, server, "https://$host/")
        }

        val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            val resolvedSrc = resolveUrl(sourceSrc, embedUrl)
            return extractDirectM3u8(resolvedSrc, server, "https://$host/")
        }

        val jsVarUrl = JS_VAR_M3U8_REGEX.find(pageBody)?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)
        }
        if (jsVarUrl != null) {
            val resolvedUrl = resolveUrl(jsVarUrl, embedUrl)
            if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains("/stream/")) {
                return try {
                    fetchSourcesFromPage(resolvedUrl, server, "https://$host/")
                } catch (_: Exception) {
                    extractDirectM3u8(resolvedUrl, server, "https://$host/")
                }
            }
        }

        return ExtractionResult(emptyList(), false)
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        server: AnikotoVideoData,
    ): ExtractionResult {
        val streamType = try {
            embedUrl.toHttpUrl().pathSegments.lastOrNull()
                ?.takeIf { it == "sub" || it == "dub" }
        } catch (_: Exception) {
            null
        } ?: ""

        val apiHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        val (data, usedGetSourcesNew) = fetchSourceData(dataId, host, apiHeaders, streamType)

        val m3u8 = data.sources.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found")

        val subtitles = data.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        val videos = playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "${server.serverName}$typeSuffix - ${cleanHlsQuality(quality)}"
            },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, usedGetSourcesNew)
    }

    private suspend fun fetchSourceData(
        dataId: String,
        host: String,
        apiHeaders: Headers,
        streamType: String,
    ): Pair<AnikotoSourceResponseDto, Boolean> {
        val primaryResult = try {
            val data = client.newCall(GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders))
                .awaitSuccess().use { it.parseAs<AnikotoSourceResponseDto>() }
            data to false
        } catch (_: Exception) {
            null
        }

        if (primaryResult != null) return primaryResult

        val newUrl = if (streamType.isNotEmpty()) {
            "https://$host/stream/getSourcesNew?id=$dataId&id=$dataId&type=$streamType&type=$streamType"
        } else {
            "https://$host/stream/getSourcesNew?id=$dataId&id=$dataId"
        }

        val data = client.newCall(GET(newUrl, apiHeaders))
            .awaitSuccess().use { it.parseAs<AnikotoSourceResponseDto>() }

        return data to true
    }

    private suspend fun fetchSourcesFromPage(
        url: String,
        server: AnikotoVideoData,
        referer: String,
    ): ExtractionResult {
        val pageHeaders = headers.newBuilder()
            .add("Referer", referer)
            .build()

        val body = client.newCall(GET(url, pageHeaders)).awaitSuccess().use {
            it.body.string()
        }

        if (body.trimStart().startsWith("#EXTM3U")) {
            return extractDirectM3u8(url, server, referer)
        }

        val m3u8 = M3U8_REGEX.find(body)?.groupValues?.get(0)
            ?: throw Exception("No m3u8 found in page")

        return extractDirectM3u8(m3u8, server, referer)
    }

    private suspend fun extractDirectM3u8(
        m3u8Url: String,
        server: AnikotoVideoData,
        referer: String = "$baseUrl/",
    ): ExtractionResult {
        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

        val vidHeaders = headers.newBuilder()
            .set("Referer", referer)
            .build()

        val videos = playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality ->
                "${server.serverName}$typeSuffix - ${cleanHlsQuality(quality)}"
            },
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, false)
    }

    private suspend fun extractFromMewcdnPlayer(
        embedUrl: String,
        server: AnikotoVideoData,
    ): ExtractionResult {
        val fragment = embedUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotEmpty() }
            ?: throw Exception("No fragment found in mewcdn player URL")

        val rawM3u8 = String(Base64.decode(fragment, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!rawM3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL decoded from mewcdn fragment")
        }

        val pageHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()

        val hostMap = client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use { response ->
            parseHostMap(response.body.string())
        }

        val m3u8 = applyHostMap(rawM3u8, hostMap)

        val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""

        val vidHeaders = headers.newBuilder()
            .set("Referer", "https://mewcdn.online/")
            .set("Origin", "https://mewcdn.online")
            .build()

        val videos = playlistUtils.extractFromHls(
            m3u8,
            videoNameGen = { quality ->
                "${server.serverName}$typeSuffix - ${cleanHlsQuality(quality)}"
            },
            referer = "https://mewcdn.online/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, true)
    }

    // =================================================================
    // M3U8 proxy
    // =================================================================

    private suspend fun proxyVideoList(videos: List<Video>): List<Video> {
        if (!m3u8ServerManager.isRunning()) return emptyList()
        return videos.mapNotNull { video ->
            val processedUrl = try {
                m3u8ServerManager.processM3u8Url(video.url)
            } catch (_: Exception) {
                null
            }
            processedUrl?.let {
                Video(
                    url = it,
                    quality = video.quality,
                    videoUrl = it,
                    headers = video.headers,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            }
        }
    }

    // =================================================================
    // Helpers
    // =================================================================

    private fun cleanHlsQuality(quality: String): String =
        quality.substringBefore(" (").substringBefore(" - ")

    private fun parseHostMap(html: String): Map<String, String> {
        val mapMatch = HOST_MAP_REGEX.find(html) ?: return emptyMap()
        return HOST_ENTRY_REGEX.findAll(mapMatch.groupValues[1]).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun applyHostMap(url: String, hostMap: Map<String, String>): String {
        var result = url
        for ((origin, proxy) in hostMap) {
            if (result.contains(origin)) {
                result = result.replace(origin, proxy)
                break
            }
        }
        return result
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        val baseUrl = try {
            base.toHttpUrl()
        } catch (_: Exception) {
            return url
        }
        return baseUrl.resolve(url)?.toString() ?: url
    }

    companion object {
        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
        private val JS_VAR_M3U8_REGEX = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""",
        )
        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}
