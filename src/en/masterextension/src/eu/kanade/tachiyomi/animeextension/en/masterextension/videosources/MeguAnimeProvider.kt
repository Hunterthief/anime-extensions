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
        val meta = try {
            EpisodeMeta.from(episode)
        } catch (e: Exception) {
            return dbg("META ERR: ${e.message?.take(60)}")
        }
        val anilistId = meta.anilistId

        val episodeDataUrl = "$baseUrl/lib/$anilistId.json"
        val episodeResp = try {
            client.newCall(GET(episodeDataUrl, headers)).awaitSuccess()
        } catch (e: Exception) {
            return dbg("EPISODE JSON ERR: ${e.message?.take(60)}")
        }
        
        val episodeData = try {
            json.parseToJsonElement(episodeResp.body.string()).jsonObject
        } catch (e: Exception) {
            return dbg("PARSE JSON ERR: ${e.message?.take(60)}")
        }

        val epKey = "ep${meta.epNum}"
        val epObj = episodeData[epKey]?.jsonObject
        if (epObj == null) {
            return dbg("NO EP KEY: '$epKey'. Available keys: ${episodeData.keys.take(5)}")
        }

        if (playerJsContent == null) {
            val playerResp = try {
                client.newCall(GET(playerJsUrl, headers)).awaitSuccess()
            } catch (e: Exception) {
                return dbg("PLAYER JS ERR: ${e.message?.take(60)}")
            }
            playerJsContent = playerResp.body.string()
        }

        return extractVideosFromEpisode(epObj, meta.epNum)
    }

    private fun extractVideosFromEpisode(epObj: JsonObject, epNum: Int): List<Video> {
        val videos = mutableListOf<Video>()
        val playerJs = playerJsContent ?: return dbg("NO PLAYER JS CACHED")

        val base64Regex = Regex("""eval\(atob\(['"]([^'"]+)['"]\)""")
        val base64Match = base64Regex.find(playerJs)
        if (base64Match == null) {
            return dbg("NO BASE64 MATCH IN player.js. Snippet: ${playerJs.take(150)}")
        }

        val decodedBlob = try {
            String(Base64.decode(base64Match.groupValues[1], Base64.DEFAULT))
        } catch (e: Exception) {
            return dbg("BASE64 DECODE ERR: ${e.message?.take(60)}")
        }
        
        val serversRegex = Regex("""var\s+servers\s*=\s*(\{[^}]+\})""")
        val serversMatch = serversRegex.find(decodedBlob)
        if (serversMatch == null) {
            return dbg("NO SERVERS MATCH. Blob snippet: ${decodedBlob.take(150)}")
        }

        val serversStr = serversMatch.groupValues[1]
        val serverConfigs = parseServerConfig(serversStr)
        if (serverConfigs.isEmpty()) {
            return dbg("NO SERVER CONFIGS PARSED. Str: $serversStr")
        }

        for ((serverName, srcElement) in epObj) {
            if (serverName == "filler") continue
            val srcUrl = srcElement.jsonPrimitive.content
            val config = serverConfigs[serverName] ?: continue

            try {
                val videoUrl = decryptStreamUrl(srcUrl, config.key, config.iv)
                if (videoUrl.isNotBlank()) {
                    videos.add(
                        Video(
                            url = videoUrl,
                            quality = "$name - ${serverName.uppercase()}",
                            videoUrl = videoUrl
                        )
                    )
                }
            } catch (e: Exception) {
                return dbg("DECRYPT ERR ($serverName): ${e.message?.take(60)}")
            }
        }

        if (videos.isEmpty()) {
            return dbg("0 VIDEOS EXTRACTED. EpObj keys: ${epObj.keys}")
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

        val encryptedBytes = Base64.decode(encryptedUrl, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun dbg(msg: String): List<Video> =
        listOf(Video("debug://x", msg.take(120), "debug://x"))
}
