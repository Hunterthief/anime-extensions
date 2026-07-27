package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animenosub

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MoonExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun videosFromUrl(url: String, prefix: String): List<Video> {
        return try {
            val userAgent = headers["User-Agent"]?.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val videoId = httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()

            val detailsHeaders = headers.newBuilder()
                .set("Referer", "$siteUrl/")
                .set("Origin", siteUrl)
                .set("User-Agent", userAgent)
                .build()

            val detailsBody = client.newCall(
                GET("https://$host/api/videos/$videoId/embed/details", detailsHeaders)
            ).awaitSuccess().bodyString()

            val detailsResponse = try { detailsBody.parseAs<DetailsResponse>(json) } catch (_: Exception) { return emptyList() }
            val embedUrl = detailsResponse.embedFrameUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
            val embedHost = embedUrl.toHttpUrl().host

            val viewerId = UUID.randomUUID().toString().replace("-", "")
            val deviceId = UUID.randomUUID().toString().replace("-", "")
            val nowSec = System.currentTimeMillis() / 1000
            val expSec = nowSec + 600

            val fingerprintPayload = """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.93,"iat":$nowSec,"exp":$expSec}"""
            val payloadB64 = Base64.encodeToString(fingerprintPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val fingerprintToken = "$payloadB64.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

            val fingerprintBody = FingerprintRequest(FingerprintData(fingerprintToken, viewerId, deviceId, 0.93))
            val playbackHeaders = headers.newBuilder()
                .set("Referer", embedUrl)
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .set("X-Embed-Origin", siteUrl.removePrefix("https://"))
                .set("X-Embed-Parent", "https://$host/e/$videoId")
                .set("X-Embed-Referer", "$siteUrl/")
                .build()

            val requestBody = json.encodeToString(fingerprintBody).toJsonRequestBody()
            val playbackUrl = "https://$embedHost/api/videos/$videoId/embed/playback"

            val response = client.newCall(POST(playbackUrl, playbackHeaders, requestBody)).awaitSuccess().bodyString().parseAs<PlaybackResponse>(json)

            val masterUrl = (
                response.sources?.firstOrNull()?.let { it.url ?: it.file }
                    ?: response.playback?.let { playback ->
                        val decrypted = decryptPayload(playback)
                        decrypted.parseAs<InnerResponse>(json).sources?.firstOrNull()?.let { it.url ?: it.file }
                    }
            )?.takeIf { it.isNotBlank() } ?: return emptyList()

            val videoHeaders = Headers.Builder()
                .set("Referer", "https://$embedHost/")
                .set("Origin", "https://$embedHost")
                .set("User-Agent", userAgent)
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality ->
                    listOfNotNull(prefix.trim().takeIf { it.isNotBlank() }, "Moon -".takeIf { !prefix.contains("Moon", true) }, quality.trim()).joinToString(" ")
                },
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decryptPayload(pb: PlaybackData): String {
        val keyBytes = decodeB64Url(pb.keyParts[0]) + decodeB64Url(pb.keyParts[1])
        val ivBytes = decodeB64Url(pb.iv)
        val cipherBytes = decodeB64Url(pb.payload)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        return cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
    }

    private fun decodeB64Url(input: String): ByteArray {
        val padding = when (input.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
        return Base64.decode(input + padding, Base64.URL_SAFE)
    }

    @Serializable private data class DetailsResponse(@SerialName("embed_frame_url") val embedFrameUrl: String? = null)
    @Serializable private data class FingerprintRequest(val fingerprint: FingerprintData)
    @Serializable private data class FingerprintData(val token: String, @SerialName("viewer_id") val viewerId: String, @SerialName("device_id") val deviceId: String, val confidence: Double)
    @Serializable private data class PlaybackResponse(val sources: List<VideoSource>? = null, val playback: PlaybackData? = null)
    @Serializable private data class PlaybackData(val iv: String, val payload: String, @SerialName("key_parts") val keyParts: List<String>)
    @Serializable private data class InnerResponse(val sources: List<VideoSource>? = null)
    @Serializable private data class VideoSource(val file: String? = null, val url: String? = null)
}
