package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MeguAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {
    override val name = "MeguAnime"
    override val baseUrl = "https://meguanime.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private var lastDebug: String? = null

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        lastDebug = null

        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(60)}")
        }

        val subVideos = fetchLanguageVideos(meta.anilistId, meta.epNum, "sub", "Sub")
        val dubVideos = fetchLanguageVideos(meta.anilistId, meta.epNum, "dub", "Dub")
        val allVideos = subVideos + dubVideos

        if (allVideos.isEmpty()) {
            return dbg(lastDebug ?: "0 VIDEOS FOUND. Ep ${meta.epNum} may be missing.")
        }

        return allVideos
    }

    private suspend fun fetchLanguageVideos(
        anilistId: Int,
        epNum: Int,
        lang: String,
        label: String,
    ): List<Video> {
        return try {
            val apiUrl = "$baseUrl/api/vidnest?al=$anilistId&ep=$epNum&lang=$lang"
            val apiResp = client.newCall(GET(apiUrl, headers)).await()

            if (!apiResp.isSuccessful) {
                lastDebug = "$label API HTTP ${apiResp.code}"
                return emptyList()
            }

            val apiData = json.parseToJsonElement(apiResp.body.string()).jsonObject
            val source = apiData["source"]?.jsonPrimitive?.content

            if (source.isNullOrBlank()) {
                lastDebug = "$label: API returned no source"
                return emptyList()
            }

            val subtitles = parseSubtitles(apiData)

            val workingHeaders = findWorkingHeaders(source, label)
            if (workingHeaders == null) {
                return emptyList()
            }

            val playlistText = fetchFullText(source, workingHeaders) ?: ""
            val videos = mutableListOf<Video>()

            if (playlistText.startsWith("#EXTM3U")) {
                // Direct master playlist entry. Often more reliable than extracted variants.
                videos.add(
                    Video(
                        url = source,
                        quality = "$name - $label Auto [tested]",
                        videoUrl = source,
                        headers = workingHeaders,
                        subtitleTracks = subtitles,
                    )
                )

                val playlistUtils = PlaylistUtils(client, workingHeaders)
                val variants = try {
                    playlistUtils.extractFromHls(
                        source,
                        videoNameGen = { "$name - $label - $it" },
                        subtitleList = subtitles,
                        masterHeaders = workingHeaders,
                        videoHeaders = workingHeaders,
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                videos.addAll(variants)
            } else {
                videos.add(
                    Video(
                        url = source,
                        quality = "$name - $label Direct [tested]",
                        videoUrl = source,
                        headers = workingHeaders,
                        subtitleTracks = subtitles,
                    )
                )
            }

            videos
        } catch (e: Exception) {
            lastDebug = "$label ERR: ${e.message?.take(60)}"
            emptyList()
        }
    }

    private fun parseSubtitles(apiData: kotlinx.serialization.json.JsonObject): List<Track> {
        val tracks = apiData["tracks"]?.jsonArray ?: return emptyList()

        return tracks.mapNotNull { track ->
            val trackObj = track.jsonObject
            val file = trackObj["file"]?.jsonPrimitive?.content
            val label = trackObj["label"]?.jsonPrimitive?.content ?: "Unknown"

            if (!file.isNullOrBlank()) {
                Track(file, label)
            } else {
                null
            }
        }
    }

    private suspend fun findWorkingHeaders(source: String, label: String): Headers? {
        val candidates = candidateHeaders(source)

        for (candidate in candidates) {
            if (canPlaySource(source, candidate)) {
                return candidate
            }
        }

        lastDebug = "$label: all header tests blocked by CDN"
        return null
    }

    private fun candidateHeaders(source: String): List<Headers> {
        val list = mutableListOf<Headers>()

        fun add(referer: String?, origin: String?, secFetch: Boolean) {
            val builder = Headers.Builder()
                .set("User-Agent", userAgent)
                .set("Accept", "*/*")

            if (referer != null) builder.set("Referer", referer)
            if (origin != null) builder.set("Origin", origin)

            if (secFetch) {
                builder.set("Sec-Fetch-Dest", "video")
                builder.set("Sec-Fetch-Mode", "no-cors")
                builder.set("Sec-Fetch-Site", "cross-site")
            }

            list.add(builder.build())
        }

        // Most likely combos first
        add(source, baseUrl, true)
        add("$baseUrl/", baseUrl, true)
        add(source, null, true)
        add("$baseUrl/", null, true)
        add(source, baseUrl, false)
        add("$baseUrl/", baseUrl, false)

        // Fallbacks using the extension's default headers
        list.add(
            headers.newBuilder()
                .set("User-Agent", userAgent)
                .set("Referer", source)
                .build()
        )

        list.add(
            headers.newBuilder()
                .set("User-Agent", userAgent)
                .set("Referer", "$baseUrl/")
                .build()
        )

        return list
    }

    private suspend fun canPlaySource(source: String, requestHeaders: Headers): Boolean {
        return try {
            client.newCall(GET(source, requestHeaders)).await().use { resp ->
                if (!resp.isSuccessful) {
                    return@use false
                }

                val contentType = resp.header("Content-Type") ?: ""
                val preview = resp.peekBody(8192).string()

                if (preview.startsWith("#EXTM3U")) {
                    val fullPlaylist = fetchFullText(source, requestHeaders) ?: preview
                    return@use testHlsStream(source, fullPlaylist, requestHeaders)
                }

                if (preview.contains("<MPD")) {
                    return@use true
                }

                !contentType.contains("text/html", true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun testHlsStream(
        masterUrl: String,
        masterText: String,
        requestHeaders: Headers,
    ): Boolean {
        val variants = parseVariantUris(masterText)
        val mediaPlaylists = mutableListOf<Pair<String, String>>()

        if (variants.isEmpty()) {
            mediaPlaylists.add(masterUrl to masterText)
        } else {
            for (variant in variants.take(4)) {
                val variantUrl = resolveUrl(masterUrl, variant) ?: continue
                val variantText = fetchFullText(variantUrl, requestHeaders) ?: continue

                if (variantText.startsWith("#EXTM3U")) {
                    mediaPlaylists.add(variantUrl to variantText)
                }
            }
        }

        if (mediaPlaylists.isEmpty()) {
            return false
        }

        for ((mediaUrl, mediaText) in mediaPlaylists) {
            if (testMediaPlaylist(mediaUrl, mediaText, requestHeaders)) {
                return true
            }
        }

        return false
    }

    private suspend fun testMediaPlaylist(
        mediaUrl: String,
        mediaText: String,
        requestHeaders: Headers,
    ): Boolean {
        // Test AES-128 key if present. If key is blocked, playback will skip/fail.
        val keyUris = Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""")
            .findAll(mediaText)
            .map { it.groupValues[1] }
            .toList()

        for (keyUri in keyUris.take(2)) {
            val keyUrl = resolveUrl(mediaUrl, keyUri) ?: return false
            if (!testUrl(keyUrl, requestHeaders)) {
                return false
            }
        }

        // Test init segment for fMP4 HLS if present.
        val mapUri = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""")
            .find(mediaText)
            ?.groupValues
            ?.get(1)

        if (mapUri != null) {
            val mapUrl = resolveUrl(mediaUrl, mapUri) ?: return false
            if (!testUrl(mapUrl, requestHeaders)) {
                return false
            }
        }

        // Test first real media segment.
        val segmentUri = mediaText.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.trim()
            ?: return false

        val segmentUrl = resolveUrl(mediaUrl, segmentUri) ?: return false
        return testUrl(segmentUrl, requestHeaders)
    }

    private suspend fun testUrl(url: String, requestHeaders: Headers): Boolean {
        return try {
            val rangeHeaders = requestHeaders.newBuilder()
                .set("Range", "bytes=0-1")
                .build()

            val rangeResult = client.newCall(GET(url, rangeHeaders)).await().use { resp ->
                if (resp.code == 416) {
                    null
                } else {
                    val contentType = resp.header("Content-Type") ?: ""
                    resp.isSuccessful && !contentType.contains("text/html", true)
                }
            }

            if (rangeResult != null) {
                return rangeResult
            }

            client.newCall(GET(url, requestHeaders)).await().use { resp ->
                val contentType = resp.header("Content-Type") ?: ""
                resp.isSuccessful && !contentType.contains("text/html", true)
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchFullText(url: String, requestHeaders: Headers): String? {
        return try {
            client.newCall(GET(url, requestHeaders)).await().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    resp.body.string()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVariantUris(masterText: String): List<String> {
        val lines = masterText.lines()
        val variants = mutableListOf<String>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val inlineUri = Regex("""URI="([^"]+)"""")
                    .find(line)
                    ?.groupValues
                    ?.get(1)

                if (inlineUri != null) {
                    variants.add(inlineUri)
                } else {
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) {
                        j++
                    }

                    if (j < lines.size && !lines[j].trim().startsWith("#")) {
                        variants.add(lines[j].trim())
                    }
                }
            }

            i++
        }

        return variants
    }

    private fun resolveUrl(baseUrl: String, uri: String): String? {
        return try {
            baseUrl.toHttpUrl().resolve(uri)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
