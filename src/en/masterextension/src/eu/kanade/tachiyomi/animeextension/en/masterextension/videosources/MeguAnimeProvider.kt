package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MeguAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {
    override val name = "MeguAnime"
    override val baseUrl = "https://meguanime.com"

    private val json = Json { ignoreUnknownKeys = true }
    private val playerJsUrl = "$baseUrl/player.js"
    private var playerJsContent: String? = null

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val anilistId = meta.anilistId

            val episodeDataUrl = "$baseUrl/lib/$anilistId.json"
            val episodeResp = client.newCall(GET(episodeDataUrl, headers)).awaitSuccess()
            val episodeData = json.parseToJsonElement(episodeResp.body.string()).jsonObject

            val epKey = "ep${meta.epNum}"
            val epObj = episodeData[epKey]?.jsonObject ?: return emptyList()

            if (playerJsContent == null) {
                val playerResp = client.newCall(GET(playerJsUrl, headers)).awaitSuccess()
                playerJsContent = playerResp.body.string()
            }

            extractVideosFromEpisode(epObj, meta.epNum)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVideosFromEpisode(epObj: JsonObject, epNum: Int): List<Video> {
        val videos = mutableListOf<Video>()
        val playerJs = playerJsContent ?: return emptyList()

        val base64Regex = Regex("""eval\(atob\(['"]([^'"]+)['"]\)""")
        val base64Match = base64Regex.find(playerJs) ?: return emptyList()

        // FIX 1: Use Android's Base64.decode instead of java.util.Base64.getDecoder()
        val decodedBlob = String(Base64.decode(base64Match.groupValues[1], Base64.DEFAULT))
        
        val serversRegex = Regex("""var\s+servers\s*=\s*(\{[^}]+\})""")
        val serversMatch = serversRegex.find(decodedBlob) ?: return emptyList()

        val serversStr = serversMatch.groupValues[1]
        val serverConfigs = parseServerConfig(serversStr)

        for ((serverName, srcElement) in epObj) {
            if (serverName == "filler") continue
            
            // FIX 2: Use .content instead of .contentOrNull
            val srcUrl = srcElement.jsonPrimitive.content
            val config = serverConfigs[serverName] ?: continue

            try {
                val videoUrl = decryptStreamUrl(srcUrl, config.key, config.iv)
                if (videoUrl.isNotBlank()) {
                    // FIX 3: Provide all required parameters to the Video constructor
                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = "$name - ${serverName.uppercase()}",
                            videoUrl = videoUrl
                        )
                    )
                }
            } catch (e: Exception) {
                continue
            }
        }

        return videos
    }

    private data class ServerConfig(val key: String, val iv: String)

    private fun parseServerConfig(configStr: String): Map<String, ServerConfig> {
        val configs = mutableMapOf<String, ServerConfig>()
        val entryRegex = Regex("""(\w+):\{key:\s*"([^"]+)",\s*iv:\s*"([^"]+)""")

        for (match in entryRegex.findAll(configStr)) {
            val name = match.groupValues[1]
            val key = match.groupValues[2]
            val iv = match.groupValues[3]
            configs[name] = ServerConfig(key, iv)
        }
        return configs
    }

    private fun decryptStreamUrl(encryptedUrl: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        // FIX 4: Use Android's Base64.decode here as well
        val encryptedBytes = Base64.decode(encryptedUrl, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }
}
