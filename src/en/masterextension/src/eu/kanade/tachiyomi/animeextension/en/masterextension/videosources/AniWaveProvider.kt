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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
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
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniWaveProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniWave"

    companion object {
        private const val BASE_URL = "https://animewave.to"

        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val EP_URL_SUFFIX_REGEX = Regex("""/ep-\d+$""")

        // VRF encryption keys (from AnikotoUtils)
        private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
        private const val RC4_KEY_1 = "ItFKjuWokn4ZpB"
        private const val RC4_KEY_2 = "fOyt97QWFB3"
        private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
        private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
        private const val RC4_KEY_3 = "736y1uTJpBLUX"
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
        val encodedTitle = java.net.URLEncoder.encode(title, "utf-8")
        val url = "$BASE_URL/filter?keyword=$encodedTitle&vrf=$vrf"
        return try {
            val doc = client.newCall(GET(url, docHeaders)).awaitSuccess().asJsoup()
            val firstResult = doc.selectFirst("div.ani.items > div.item a.name")
                ?: doc.selectFirst("div.item a[href*=/watch/]")
                ?: doc.selectFirst("a[href*=/watch/]")
            val href = firstResult?.attr("href") ?: return null
            EP_URL_SUFFIX_REGEX.replace(href.substringBefore("?"), "")
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
    // STEP 4: Get server list → server IDs and names
    // =================================================================

    private data class ServerInfo(val id: String, val name: String, val type: String)

    private suspend fun getServerList(episodeIds: String, epUrl: String): List<ServerInfo> {
        val url = "$BASE_URL/ajax/server/list?servers=$episodeIds"
        return try {
            val responseBody = client.newCall(GET(url, xhrHeaders("$BASE_URL$epUrl")))
                .awaitSuccess().bodyString()
            val result = responseBody.parseAs<ResultResponse>()
            val doc = Jsoup.parseBodyFragment(result.result)

            doc.select("div.servers > div.type").flatMap { typeElem ->
                val label = typeElem.selectFirst("label")?.text()?.trim()
                    ?: typeElem.attr("data-type")
                    ?: "Sub"

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
    // STEP 5: Get embed URL for a server
    // =================================================================

    private suspend fun getEmbedUrl(serverId: String, epUrl: String): String? {
        return try {
            val responseBody = client.newCall(
                GET("$BASE_URL/ajax/server?get=$serverId", xhrHeaders("$BASE_URL$epUrl")),
            ).awaitSuccess().bodyString()
            responseBody.parseAs<ServerResponseDto>().result.url
        } catch (_: Exception) {
            null
        }
    }

    // =================================================================
    // STEP 6: Extract m3u8 from embed page
    // =================================================================

    private suspend fun extractFromEmbed(embedUrl: String, server: ServerInfo): List<Video> {
        val host = try { embedUrl.toHttpUrl().host } catch (_: Exception) { return emptyList() }

        val pageHeaders = headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .build()

        val pageBody = try {
            client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().bodyString()
        } catch (_: Exception) {
            return emptyList()
        }

        // Strategy 1: data-id → getSources API (AnikotoExtractor approach)
        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, server)
        }

        // Strategy 2: iframe redirect
        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = if (iframeSrc.startsWith("http")) iframeSrc
            else embedUrl.toHttpUrl().resolve(iframeSrc)?.toString() ?: iframeSrc
            return extractFromEmbed(resolvedSrc, server)
        }

        // Strategy 3: direct m3u8 in page
        val m3u8Url = M3U8_REGEX.find(pageBody)?.value
        if (m3u8Url != null) {
            return extractHls(m3u8Url, server, "https://$host/")
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

        // Try primary endpoint
        val data = try {
            val body = client.newCall(
                GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders),
            ).awaitSuccess().bodyString()
            body.parseAs<SourceResponseDto>()
        } catch (_: Exception) {
            // Try fallback endpoint
            try {
                val body = client.newCall(
                    GET("https://$host/stream/getSourcesNew?id=$dataId&id=$dataId", apiHeaders),
                ).awaitSuccess().bodyString()
                body.parseAs<SourceResponseDto>()
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
            val servers = getServerList(episodeIds, epUrl)
            if (servers.isEmpty()) return emptyList()

            // Step 5+6: For each server → embed URL → extract
            servers.flatMap { server ->
                runCatching {
                    val embedUrl = getEmbedUrl(server.id, epUrl) ?: return@flatMap emptyList()
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
