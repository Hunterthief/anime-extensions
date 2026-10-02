package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.net.URLEncoder

object SettlarProxy : NanoHTTPD(0) {
    val port: Int get() = super.getListeningPort()
    
    @Volatile private var isRunning = false
    @Volatile private var client: OkHttpClient? = null
    
    private val settlarHeaders = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", "https://ani.pm/")
        .set("Origin", "https://ani.pm")
        .build()

    override fun start() {
        super.start()
        isRunning = true
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    fun processVideoList(okClient: OkHttpClient, videos: List<Video>): List<Video> {
        this.client = okClient
        ensureStarted()
        return videos.map { video ->
            if (video.url.contains(".m3u8", ignoreCase = true)) {
                val encodedUrl = URLEncoder.encode(video.url, "UTF-8")
                Video(
                    "http://localhost:$port/proxy?url=$encodedUrl",
                    video.quality,
                    video.url,
                    video.headers,
                    video.subtitleTracks,
                    video.audioTracks
                )
            } else {
                video
            }
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) start()
    }

    override fun handle(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return Response.newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
            
        return try {
            val reqClient = client ?: throw Exception("Client not initialized")
            val request = Request.Builder().url(url).headers(settlarHeaders).build()
            val response = reqClient.newCall(request).execute()
            
            val bodyBytes = response.body.bytes()
            val contentType = response.header("Content-Type") ?: "application/vnd.apple.mpegurl"
            
            // If it's an m3u8 playlist, rewrite segment URLs to also go through the proxy
            if (contentType.contains("mpegurl", ignoreCase = true) || url.endsWith(".m3u8")) {
                val playlist = String(bodyBytes)
                val rewritten = rewritePlaylist(playlist, url)
                Response.newFixedLengthResponse(Status.OK, contentType, rewritten)
            } else {
                // It's a .ts segment or other media
                Response.newChunkedResponse(Status.OK, contentType, ByteArrayInputStream(bodyBytes))
            }
        } catch (e: Exception) {
            Response.newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy Error: ${e.message}")
        }
    }

    private fun rewritePlaylist(content: String, baseUrl: String): String {
        val lines = content.lines()
        return lines.joinToString("\n") { line ->
            if (line.startsWith("#") || line.isBlank()) {
                line
            } else {
                val absoluteUrl = if (line.startsWith("http")) line else resolveUrl(baseUrl, line)
                val encoded = URLEncoder.encode(absoluteUrl, "UTF-8")
                "http://localhost:$port/proxy?url=$encoded"
            }
        }
    }
    
    private fun resolveUrl(base: String, relative: String): String {
        return try {
            base.toHttpUrl().resolve(relative)?.toString() ?: relative
        } catch (e: Exception) {
            relative
        }
    }
}
