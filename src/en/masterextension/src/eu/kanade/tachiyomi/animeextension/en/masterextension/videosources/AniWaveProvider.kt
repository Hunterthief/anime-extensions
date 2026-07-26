package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import android.util.Base64
import android.util.Log
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
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AniWave provider — based on AnikotoTheme + AnikotoExtractor.
 * Flow: VRF-encrypted search → anime page → episode AJAX → server AJAX →
 *       embed URL → data-id → getSources API → m3u8
 */
class AniWaveProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniWave"

    companion object {
        private const val TAG = "AniWaveProv"
        private const val BASE_URL = "https://animewave.to"

        // VRF encryption keys (from AnikotoUtils)
        private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
        private const val KEY_1 = "ItFKjuWokn4ZpB"
        private const val KEY_2 = "fOyt97QWFB3"
        private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
        private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
        private const val KEY_3 = "736y1uTJpBLUX"

        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val docHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()
    }

    private fun xhrHeaders(referer: String): Headers {
        return headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("Referer", referer)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // =================================================================
    // VRF Encryption (from AnikotoUtils)
    // =================================================================

    private fun vrfEncrypt(input: String): String {
        var vrf = input
        vrf = exchange(vrf, EXCHANGE_KEY_1[0], EXCHANGE_KEY_1[1])
        vrf = rc4Encrypt(KEY_1, vrf)
        vrf = rc4Encrypt(KEY_2, vrf)
        vrf = exchange(vrf, EXCHANGE_KEY_2[0], EXCHANGE_KEY_2[1])
        vrf = exchange(vrf, EXCHANGE_KEY_3[0], EXCHANGE_KEY_3[1])
        vrf = vrf.reversed()
        vrf = rc4Encrypt(KEY_3, vrf)
        vrf = Base64.encode(vrf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
        return java.net.URLEncoder.encode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.parameters)
        val output = cipher.doFinal(input.toByteArray())
        return Base64.encode(output, Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
    }

    private fun exchange(input: String, key1: String, key2: String): String {
        return input.map { ch ->
            val idx = key1.indexOf(ch)
            if (idx != -1) key2[idx] else ch
        }.joinToString("")
    }

    // =================================================================
    // STEP 1: Search by title → anime path
    // =================================================================

    private suspend fun searchAnime(title: String): String? {
        val vrf = vrfEncrypt(title)
        val url = "$BASE_URL/filter?keyword=${java.net.URLEncoder.encode(title, "utf-8")}&vrf=$vrf"
        return try {
            val doc = client.newCall(GET(url, docHeaders)).awaitSuccess().asJsoup()
            val firstResult = doc.selectFirst("div.ani.items > div.item a.name")
                ?: doc.selectFirst("div.item a[href*=/watch/]")
            val href = firstResult?.attr("href") ?: return null
            EP_URL_SUFFIX_REGEX.replace(href.substringBefore("?"), "")
        } catch (e: Exception) {
            Log.e(TAG, "searchAnime failed: ${e.message}")
            null
        }
    }

    // =================================================================
    // STEP 2: Get anime page → data-id
    // =================================================================

    private suspend fun getAnimeId(animePath: String): String? {
        return try {
            val doc = client.newCall(GET("$BASE_URL$animePath", docHeaders))
                .awaitSuccess().asJsoup()
            doc.selectFirst("[data-id]")?.attr("data-id")
                ?: doc.selectFirst("[data-tip]")?.attr("data-tip")
        } catch (e: Exception) {
            Log.e(TAG, "getAnimeId failed: ${e.message}")
            null
        }
    }

    // =================================================================
    // STEP 3: Get episode list → find episode's data-ids
    // =================================================================

    private suspend fun getEpisodeIds(animeId: String, epNum: Int, animePath: String): String? {
        val vrf = vrfEncrypt(animeId)
        val url = "$BASE_URL/ajax/episode/list/$animeId?vrf=$vrf"
        return try {
            val responseBody = client.newCall(GET(url, xhrHeaders("$BASE_URL$animePath")))
                .awaitSuccess().bodyString()
            val result = responseBody.parseAs<ResultResponse>()
            val doc = Jsoup.parseBodyFragment(result.result)

            // Find the episode matching our number
            val epElement = doc.select("div.episodes ul > li > a").firstOrNull {
                it.attr("data-num") == epNum.toString()
            } ?: doc.select("div.episodes ul > li > a").firstOrNull {
                it.attr("data-num").toFloatOrNull()?.toInt() == epNum
            }

            epElement?.attr("data-ids")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodeIds failed: ${e.message}")
            null
        }
    }

    // =================================================================
    // STEP 4: Get server list → server IDs
    // =================================================================

    private suspend fun getServerList(episodeIds: String, animePath: String, epNum: Int): List<Pair<String, String>> {
        val epUrl = "$animePath/ep-$epNum"
        val url = "$BASE_URL/ajax/server/list?servers=$episodeIds"
        return try {
            val responseBody = client.newCall(GET(url, xhrHeaders("$BASE_URL$epUrl")))
                .awaitSuccess().bodyString()
            val result = responseBody.parseAs<ResultResponse>()
            val doc = Jsoup.parseBodyFragment(result.result)

            doc.select("div.servers > div.type li").mapNotNull { li ->
                if (li.hasClass("download-icon")) return@mapNotNull null
                val serverId = li.attr("data-link-id")
                val serverName = li.text().trim()
                if (serverId.isNotEmpty() && serverName.isNotEmpty()) {
                    serverId to serverName
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getServerList failed: ${e.message}")
            emptyList()
        }
    }

    // =================================================================
    // STEP 5: Get embed URL for a server
    // =================================================================

    private suspend fun getEmbedUrl(serverId: String, epUrl: String): String? {
        return try {
            val responseBody = client.newCall(
                GET("$BASE_URL/ajax/server?get=$serverId", xhrHeaders("$BASE_URL$epUrl")),
            ).awaitSuccess().bodyString()
            responseBody.parseAs<ServerResponseDto>().result.url
        } catch (e: Exception) {
            Log.e(TAG, "getEmbedUrl failed: ${e.message}")
            null
        }
    }

    // =================================================================
    // STEP 6: Extract m3u8 from embed page
    // =================================================================

    private suspend fun extractFromEmbed(embedUrl: String, serverName: String): List<Video> {
        val host = try { embedUrl.toHttpUrl().host } catch (_: Exception) { return emptyList() }

        val pageHeaders = headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()

        val pageBody = try {
            client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "embed page fetch failed: ${e.message}")
            return emptyList()
        }

        // Strategy 1: data-id → getSources API
        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, serverName)
        }

        // Strategy 2: direct m3u8 in page
        val m3u8Url = M3U8_REGEX.find(pageBody)?.value
        if (m3u8Url != null) {
            return extractHls(m3u8Url, serverName, "https://$host/")
        }

        Log.e(TAG, "No extraction strategy matched for $serverName at $embedUrl")
        return emptyList()
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        serverName: String,
    ): List<Video> {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", embedUrl)
            .set("Origin", "https://$host")
            .build()

        // Try primary endpoint
        val m3u8 = try {
            val body = client.newCall(
                GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString()
            val data = body.parseAs<SourceResponseDto>()
            data.sources.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }

        // Try fallback endpoint
        ?: try {
            val body = client.newCall(
                GET("https://$host/stream/getSourcesNew?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString()
            val data = body.parseAs<SourceResponseDto>()
            data.sources.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }

        ?: return emptyList()

        // Get subtitles
        val subtitles = try {
            val body = client.newCall(
                GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString()
            body.parseAs<SourceResponseDto>().tracks
                ?.filter { it.kind == "captions" }
                ?.map { Track(it.file, it.label) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return extractHls(m3u8, serverName, "https://$host/", subtitles)
    }

    private fun extractHls(
        m3u8Url: String,
        serverName: String,
        referer: String,
        subtitles: List<Track> = emptyList(),
    ): List<Video> {
        val vidHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", referer.removeSuffix("/"))
            .build()

        return playlistUtils.extractFromHls(
            m3u8Url,
            videoNameGen = { quality -> "$serverName - $quality" },
            subtitleList = subtitles,
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        return try {
            // Step 1: Search → anime path
            val animePath = searchAnime(title) ?: return emptyList()

            // Step 2: Anime page → data-id
            val animeId = getAnimeId(animePath) ?: return emptyList()

            // Step 3: Episode list → episode data-ids
            val episodeIds = getEpisodeIds(animeId, meta.epNum, animePath)
                ?: return emptyList()

            // Step 4: Server list
            val epUrl = "$animePath/ep-${meta.epNum}"
            val servers = getServerList(episodeIds, animePath, meta.epNum)
            if (servers.isEmpty()) return emptyList()

            // Step 5+6: For each server → embed URL → extract
            servers.flatMap { (serverId, serverName) ->
                runCatching {
                    val embedUrl = getEmbedUrl(serverId, epUrl) ?: return@flatMap emptyList()
                    extractFromEmbed(embedUrl, serverName)
                }.getOrElse { e ->
                    Log.e(TAG, "Server $serverName failed: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchVideos failed: ${e.message}")
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
        val sources: SourcesField,
        val tracks: List<TrackDto>? = null,
    ) {
        @Serializable
        data class TrackDto(val file: String, val kind: String, val label: String = "")
    }

    // Custom serializer for sources (can be string or array of objects)
    @Serializable(with = SourcesSerializer::class)
    private data class SourcesField(val value: String)

    private object SourcesSerializer : kotlinx.serialization.KSerializer<SourcesField> {
        override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "SourcesField",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SourcesField {
            val jsonDecoder = decoder as kotlinx.serialization.json.JsonDecoder
            val element = jsonDecoder.decodeJsonElement()
            val value = when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> element.content
                is kotlinx.serialization.json.JsonArray -> {
                    element.firstOrNull()?.let {
                        when (it) {
                            is kotlinx.serialization.json.JsonObject ->
                                it["file"]?.let { f -> (f as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            is kotlinx.serialization.json.JsonPrimitive -> it.content
                            else -> null
                        }
                    }
                }
                is kotlinx.serialization.json.JsonObject ->
                    element["file"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                else -> null
            } ?: ""
            return SourcesField(value)
        }

        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SourcesField) {
            encoder.encodeString(value.value)
        }
    }
}
