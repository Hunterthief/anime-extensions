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
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * LunarAnime video source.
 *
 * Primary path: LunarAnime API → FlixCloud embed → MegaCloud-style extraction
 *   1. GET api.lunaranime.ru/api/3rdprovider?anilist=$id&episode=$ep
 *   2. GET flixcloud.cc/e/$accessId → extract nonce from HTML
 *   3. GET flixcloud.cc/embed-2/v3/e-1/getSources?id=$id&_k=$nonce → m3u8 URLs
 *   4. PlaylistUtils → quality variants
 *
 * Fallback: vermillion API with multiple hosts
 */
class LunarAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "LunarAnime"

    companion object {
        private const val API_URL = "https://api.lunaranime.ru"
        private const val SITE_URL = "https://lunaranime.ru"
        private const val KEYS_URL = "https://raw.githubusercontent.com/yogesh-hacker/MegacloudKeys/refs/heads/main/keys.json"

        // Sources API endpoints to try (MegaCloud variants)
        private val SOURCES_ENDPOINTS = listOf(
            "/embed-2/v3/e-1/getSources?id=",
            "/embed-2/e-1/getSources?id=",
            "/ajax/embed-6/getSources?id=",
            "/ajax/embed-4/getSources?id=",
            "/ajax/getSources?id=",
        )

        // ID splitters for different embed URL formats
        private val ID_SPLITTERS = listOf("/e-1/", "/e/", "/embed/", "/v/")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json { ignoreUnknownKeys = true }

    // =================================================================
    // DTOs
    // =================================================================

    @Serializable
    private data class ThirdPartyResponse(
        val success: Boolean = false,
        val data: List<ThirdPartySource> = emptyList()
    )

    @Serializable
    private data class ThirdPartySource(
        val player_url: String = "",
        val server: String = "",
        val audio: String = ""
    )

    @Serializable
    private data class SourceResponseDto(
        val sources: List<SourceDto> = emptyList(),
        val encrypted: Boolean = true,
        val tracks: List<TrackDto>? = null
    )

    @Serializable
    private data class SourceDto(
        val file: String = "",
        val type: String = ""
    )

    @Serializable
    private data class TrackDto(
        val file: String = "",
        val kind: String = "",
        val label: String = ""
    )

    @Serializable
    private data class VermillionResponse(
        val success: Boolean = false,
        val data: VermillionData? = null
    )

    @Serializable
    private data class VermillionData(
        val sources: List<VermillionSource> = emptyList(),
        val subtitles: List<VermillionSubtitle> = emptyList(),
        val headers: Map<String, String>? = null
    )

    @Serializable
    private data class VermillionSource(
        val url: String = "",
        val quality: String = "auto",
        val type: String = "",
        val isM3U8: Boolean = false
    )

    @Serializable
    private data class VermillionSubtitle(
        val url: String = "",
        val lang: String = ""
    )

    // =================================================================
    // HEADERS
    // =================================================================

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", "$SITE_URL/")
            .set("Origin", SITE_URL)
            .build()
    }

    // =================================================================
    // PATH A: 3rdprovider API → FlixCloud → MegaCloud extraction
    // =================================================================

    private suspend fun fetchFromThirdParty(anilistId: Int, epNum: Int): List<Video> {
        val url = "$API_URL/api/3rdprovider".toHttpUrl().newBuilder()
            .addQueryParameter("anilist", anilistId.toString())
            .addQueryParameter("episode", epNum.toString())
            .addQueryParameter("autoplay", "true")
            .build().toString()

        val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().bodyString()
        val response = body.parseAs<ThirdPartyResponse>()
        if (!response.success) return emptyList()

        return response.data
            .filter { it.player_url.isNotBlank() }
            .parallelCatchingFlatMap { source ->
                extractFromMegaCloud(source.player_url, source.server, source.audio)
            }
    }

    // =================================================================
    // MEGACLOUD EXTRACTION (adapted from reference MegaCloudExtractor)
    // =================================================================

    private suspend fun extractFromMegaCloud(
        playerUrl: String,
        serverName: String,
        audio: String
    ): List<Video> {
        val host = playerUrl.toHttpUrl().host
        val serverUrl = "https://$host"
        val audioLabel = when (audio) {
            "dual" -> "Sub/Dub"
            "dub" -> "Dub"
            else -> "Sub"
        }

        // Step 1: Extract the embed ID from the URL
        val embedId = extractEmbedId(playerUrl) ?: return emptyList()

        // Step 2: Fetch the embed page and extract nonce
        val embedHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Referer", "$SITE_URL/")
            .build()

        val pageBody = client.newCall(GET(playerUrl, embedHeaders))
            .awaitSuccess().bodyString()

        val nonce = extractNonce(pageBody)

        // Step 3: Try sources API endpoints
        val sourceHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$serverUrl/")
            .set("Origin", serverUrl)
            .build()

        for (endpoint in SOURCES_ENDPOINTS) {
            try {
                val sourcesUrl = buildString {
                    append(serverUrl)
                    append(endpoint)
                    append(embedId)
                    if (nonce != null) {
                        append("&_k=")
                        append(nonce)
                    }
                }

                val srcBody = client.newCall(GET(sourcesUrl, sourceHeaders))
                    .awaitSuccess().bodyString()

                val data = json.parseToJsonElement(srcBody).jsonObject

                // Check if this is a valid sources response
                val sourcesArray = data["sources"] ?: continue
                val encrypted = data["encrypted"]?.jsonPrimitive?.content?.toBoolean() ?: true

                val sourcesList = json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(SourceDto.serializer()),
                    sourcesArray
                )

                if (sourcesList.isEmpty()) continue

                // Extract subtitles
                val subtitles = data["tracks"]?.let { tracksElement ->
                    runCatching {
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(TrackDto.serializer()),
                            tracksElement
                        ).filter { it.kind == "captions" }
                            .map { Track(it.file, it.label) }
                    }.getOrDefault(emptyList())
                }.orEmpty()

                // Process each source
                val videos = mutableListOf<Video>()
                for (source in sourcesList) {
                    val m3u8 = when {
                        // Not encrypted or already a direct m3u8 URL
                        !encrypted || source.file.contains(".m3u8") -> source.file

                        // Encrypted — try to decrypt
                        else -> decryptSource(source.file, nonce, serverUrl) ?: continue
                    }

                    if (!m3u8.startsWith("http")) continue

                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "$serverUrl/")
                        .set("Origin", serverUrl)
                        .build()

                    videos.addAll(
                        playlistUtils.extractFromHls(
                            m3u8,
                            videoNameGen = { quality ->
                                "$name $serverName $audioLabel $quality"
                            },
                            subtitleList = subtitles,
                            referer = "$serverUrl/",
                            masterHeaders = vidHeaders,
                            videoHeaders = vidHeaders,
                        )
                    )
                }

                if (videos.isNotEmpty()) return videos
            } catch (_: Exception) {
                continue
            }
        }

        // Step 4: Fallback — try to find m3u8 directly in the page HTML
        return extractM3u8FromPage(pageBody, host, serverName, audioLabel)
    }

    private fun extractEmbedId(url: String): String? {
        for (splitter in ID_SPLITTERS) {
            val id = url.substringAfter(splitter, "").substringBefore("?").substringBefore("&")
            if (id.isNotBlank()) return id
        }
        // Last resort: last path segment
        return url.toHttpUrl().pathSegments.lastOrNull { it.isNotBlank() }
    }

    private fun extractNonce(html: String): String? {
        // Pattern 1: single 48-char alphanumeric string wrapped in 'b'
        val match1 = Regex("""b[a-zA-Z0-9]{48}b""").find(html)
        if (match1 != null) return match1.value

        // Pattern 2: three 16-char groups wrapped in 'b'
        val match2 = Regex(
            """b([a-zA-Z0-9]{16})b.*?b([a-zA-Z0-9]{16})b.*?b([a-zA-Z0-9]{16})b"""
        ).find(html)
        if (match2 != null) {
            return match2.groupValues[1] + match2.groupValues[2] + match2.groupValues[3]
        }

        return null
    }

    private suspend fun decryptSource(
        encryptedData: String,
        nonce: String?,
        serverUrl: String
    ): String? {
        // Fetch decryption keys from GitHub
        val key = fetchDecryptionKey() ?: return null

        // Try known decryption API endpoints
        val decryptApis = listOf(
            "https://megacloud.decryptionapi.com/decode",
            "https://api.megacloud.app/decode",
        )

        for (apiUrl in decryptApis) {
            try {
                val fullUrl = buildString {
                    append(apiUrl)
                    append("?encrypted_data=")
                    append(URLEncoder.encode(encryptedData, "UTF-8"))
                    if (nonce != null) {
                        append("&nonce=")
                        append(URLEncoder.encode(nonce, "UTF-8"))
                    }
                    append("&secret=")
                    append(URLEncoder.encode(key, "UTF-8"))
                }

                val response = client.newCall(GET(fullUrl)).awaitSuccess().bodyString()
                val fileMatch = Regex(""""file"\s*:\s*"(.*?)"""").find(response)
                if (fileMatch != null) return fileMatch.groupValues[1]
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    private var cachedKey: String? = null

    private suspend fun fetchDecryptionKey(): String? {
        cachedKey?.let { return it }

        return try {
            val response = client.newCall(GET(KEYS_URL)).awaitSuccess().bodyString()
            val keyData = json.parseToJsonElement(response).jsonObject
            val key = keyData["mega"]?.jsonPrimitive?.content
            cachedKey = key
            key
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractM3u8FromPage(
        html: String,
        host: String,
        serverName: String,
        audioLabel: String
    ): List<Video> {
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file\s*[:=]\s*["'](https?://[^"']+)["']"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val m3u8Url = match.groupValues.last()
            if (!m3u8Url.startsWith("http")) continue

            val vidHeaders = headers.newBuilder()
                .set("Referer", "https://$host/")
                .set("Origin", "https://$host")
                .build()

            val videos = playlistUtils.extractFromHls(
                m3u8Url,
                videoNameGen = { quality -> "$name $serverName $audioLabel $quality" },
                referer = "https://$host/",
                masterHeaders = vidHeaders,
                videoHeaders = vidHeaders,
            )
            if (videos.isNotEmpty()) return videos
        }

        return emptyList()
    }

    // =================================================================
    // PATH B: Vermillion API fallback
    // =================================================================

    private suspend fun fetchFromVermillion(malId: Int, epNum: Int): List<Video> {
        val hosts = listOf("animeonsen", "kiwi", "flixcloud", "onsen", "ao")

        for (host in hosts) {
            try {
                val url = "$API_URL/api/animes/vermillion/sources".toHttpUrl().newBuilder()
                    .addQueryParameter("id", malId.toString())
                    .addQueryParameter("host", host)
                    .addQueryParameter("epNum", epNum.toString())
                    .addQueryParameter("type", "sub")
                    .build().toString()

                val body = client.newCall(GET(url, apiHeaders)).awaitSuccess().bodyString()
                val response = body.parseAs<VermillionResponse>()
                if (!response.success || response.data == null) continue

                val data = response.data
                val subtitles = data.subtitles
                    .filter { it.url.isNotBlank() }
                    .map { Track(it.url, it.lang) }

                val videos = data.sources.parallelCatchingFlatMap { source ->
                    val resolvedUrl = resolveUrl(source.url)
                    if (resolvedUrl.isBlank()) return@parallelCatchingFlatMap emptyList<Video>()

                    val refererOrigin = data.headers?.get("Origin") ?: SITE_URL
                    val vidHeaders = headers.newBuilder()
                        .set("Referer", "$refererOrigin/")
                        .set("Origin", refererOrigin)
                        .build()

                    when {
                        resolvedUrl.contains(".mpd") -> listOf(Video(
                            resolvedUrl,
                            "$name $host DASH ${source.quality}",
                            resolvedUrl,
                            headers = vidHeaders,
                            subtitleTracks = subtitles,
                        ))
                        resolvedUrl.contains(".m3u8") || source.isM3U8 -> {
                            playlistUtils.extractFromHls(
                                resolvedUrl,
                                videoNameGen = { q -> "$name $host $q" },
                                subtitleList = subtitles,
                                referer = "$refererOrigin/",
                                masterHeaders = vidHeaders,
                                videoHeaders = vidHeaders,
                            )
                        }
                        else -> listOf(Video(
                            resolvedUrl,
                            "$name $host ${source.quality}",
                            resolvedUrl,
                            headers = vidHeaders,
                            subtitleTracks = subtitles,
                        ))
                    }
                }

                if (videos.isNotEmpty()) return videos
            } catch (_: Exception) {
                continue
            }
        }

        return emptyList()
    }

    private fun resolveUrl(rawUrl: String): String {
        if (rawUrl.startsWith("http")) return rawUrl

        // Try base64url decode
        try {
            val decoded = String(
                android.util.Base64.decode(rawUrl, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
                Charsets.UTF_8
            )
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) { }

        // Try base64 with padding
        try {
            val padded = rawUrl + "=".repeat((4 - rawUrl.length % 4) % 4)
            val decoded = String(
                android.util.Base64.decode(padded, android.util.Base64.URL_SAFE),
                Charsets.UTF_8
            )
            if (decoded.startsWith("http")) return decoded
        } catch (_: Exception) { }

        return ""
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        if (meta.anilistId == 0) return emptyList()

        // Path A: 3rdprovider → FlixCloud → MegaCloud extraction
        val thirdPartyVideos = try {
            fetchFromThirdParty(meta.anilistId, meta.epNum)
        } catch (_: Exception) {
            emptyList()
        }

        if (thirdPartyVideos.isNotEmpty()) return thirdPartyVideos

        // Path B: Vermillion API fallback
        if (meta.malId != 0) {
            val vermillionVideos = try {
                fetchFromVermillion(meta.malId, meta.epNum)
            } catch (_: Exception) {
                emptyList()
            }

            if (vermillionVideos.isNotEmpty()) return vermillionVideos
        }

        return emptyList()
    }
}
