package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Torrentio video source — Torrent & Debrid aggregation.
 *
 * Uses AniList ID -> AniZip -> Kitsu ID mapping to resolve the correct media,
 * then queries the Torrentio Stremio addon for streams.
 * 
 * If no debrid token is provided, it constructs standard magnet links with 
 * anime-specific trackers (playable via Aniyomi's built-in libtorrent engine).
 * If a debrid token is provided, it returns direct HTTP stream URLs.
 */
class TorrentioProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val debridProvider: String = "none",
    private val debridToken: String = ""
) : VideoProvider {

    override val name = "Torrentio"
    override val baseUrl = "https://torrentio.strem.fun/"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val siteHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "https://torrentio.strem.fun/")
            .build()
    }

    // =================================================================
    // DEBUG HELPER
    // =================================================================
    private fun debugVideo(msg: String): List<Video> {
        return listOf(
            Video(
                url = "https://example.com/debug.m3u8",
                quality = "DEBUG: $msg",
                videoUrl = "https://example.com/debug.m3u8",
            ),
        )
    }

    // =================================================================
    // STEP 1: AniList ID -> Kitsu ID (AniZip + Kitsu fallback)
    // =================================================================
    private suspend fun getKitsuId(anilistId: Int): Pair<String?, String?> {
        val aniZipUrl = "https://api.ani.zip/mappings?anilist_id=$anilistId"
        val aniZipBody = try {
            client.newCall(GET(aniZipUrl, siteHeaders)).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return null to null
        }
        
        val aniZipResponse = try {
            json.decodeFromString<AniZipResponse>(aniZipBody)
        } catch (e: Exception) {
            null
        }

        var kitsuId = aniZipResponse?.mappings?.kitsuId?.toString()
        val type = aniZipResponse?.mappings?.type ?: "TV"

        if (kitsuId.isNullOrBlank()) {
            // Fallback to Kitsu API if AniZip doesn't have the mapping
            val kitsuUrl = "https://kitsu.io/api/edge/mappings?filter[externalSite]=anilist/anime&filter[externalId]=$anilistId&include=item"
            val kitsuBody = try {
                client.newCall(GET(kitsuUrl, siteHeaders)).awaitSuccess().bodyString()
            } catch (e: Exception) {
                return null to null
            }
            
            val kitsuResponse = try {
                json.decodeFromString<KitsuMappingsResponse>(kitsuBody)
            } catch (e: Exception) {
                null
            }
            
            kitsuId = kitsuResponse?.data?.firstOrNull()?.relationships?.item?.data?.id
        }

        return kitsuId to type
    }

    // =================================================================
    // STEP 2: Fetch Torrentio Streams
    // =================================================================
    private suspend fun fetchTorrentioStreams(kitsuId: String, type: String, epNum: Int): List<Video> {
        val streamPath = if (type == "MOVIE") {
            "/stream/movie/kitsu:$kitsuId.json"
        } else {
            "/stream/series/kitsu:$kitsuId:$epNum.json"
        }

        val torrentioUrl = buildString {
            append("https://torrentio.strem.fun/")
            append("providers=nyaasi,anidex,horriblesubs,tokyotosho,yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl|")
            append("sort=quality|")
            if (debridProvider != "none" && debridToken.isNotBlank()) {
                append("$debridProvider=$debridToken|")
            }
            append(streamPath)
        }

        val body = try {
            client.newCall(GET(torrentioUrl, siteHeaders)).awaitSuccess().bodyString()
        } catch (e: Exception) {
            return debugVideo("torrentio fetch failed: ${e.message}")
        }

        val streamData = try {
            json.decodeFromString<StreamDataTorrent>(body)
        } catch (e: Exception) {
            return debugVideo("torrentio parse failed: ${e.message}")
        }

        val streams = streamData.streams ?: return debugVideo("no streams found in torrentio response")

        // Hardcoded anime-specific trackers (matches original extension logic)
        val animeTrackers = """
        http://anidex.moe:6969/announce,
        http://tracker.anirena.com:80/announce,
        udp://tracker.uw0.xyz:6969/announce,
        http://share.camoe.cn:8080/announce,
        http://t.nyaatracker.com:80/announce,
        udp://47.ip-51-68-199.eu:6969/announce,
        udp://9.rarbg.me:2940,
        udp://9.rarbg.to:2820,
        udp://exodus.desync.com:6969/announce,
        udp://explodie.org:6969/announce,
        udp://ipv4.tracker.harry.lu:80/announce,
        udp://open.stealth.si:80/announce,
        udp://opentor.org:2710/announce,
        udp://opentracker.i2p.rocks:6969/announce,
        udp://retracker.lanta-net.ru:2710/announce,
        udp://tracker.cyberia.is:6969/announce,
        udp://tracker.dler.org:6969/announce,
        udp://tracker.ds.is:6969/announce,
        udp://tracker.internetwarriors.net:1337,
        udp://tracker.openbittorrent.com:6969/announce,
        udp://tracker.opentrackr.org:1337/announce,
        udp://tracker.tiny-vps.com:6969/announce,
        udp://tracker.torrent.eu.org:451/announce,
        udp://valakas.rollo.dnsabr.com:2710/announce,
        udp://www.torrent.eu.org:451/announce
        """.trimIndent().split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return streams.mapNotNull { stream ->
            val urlOrHash = if (debridProvider == "none" || debridToken.isBlank()) {
                val infoHash = stream.infoHash ?: return@mapNotNull null
                buildString {
                    append("magnet:?xt=urn:btih:$infoHash")
                    append("&dn=$infoHash")
                    animeTrackers.forEach { tracker ->
                        append("&tr=$tracker")
                    }
                    stream.fileIdx?.let {
                        append("&index=$it")
                    }
                }
            } else {
                stream.url ?: return@mapNotNull null
            }

            val qualityName = (stream.name?.removePrefix("Torrentio\n") ?: "") + "\n" + (stream.title ?: "")
            Video(urlOrHash, qualityName, urlOrHash)
        }
    }

    // =================================================================
    // ENTRY POINT
    // =================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val anilistId = meta.anilistId
        if (anilistId == 0) return debugVideo("anilistId is 0")

        val (kitsuId, type) = getKitsuId(anilistId)
        if (kitsuId.isNullOrBlank()) return debugVideo("kitsuId null for anilist $anilistId")

        val videos = fetchTorrentioStreams(kitsuId, type ?: "TV", meta.epNum)
        if (videos.isEmpty()) {
            return debugVideo("no videos returned from torrentio")
        }
        
        return videos
    }
}

// =================================================================
// DTOs
// =================================================================

@Serializable
private data class AniZipResponse(
    val mappings: AniZipMappings? = null,
)

@Serializable
private data class AniZipMappings(
    @SerialName("kitsu_id")
    val kitsuId: Long? = null,
    val type: String? = null,
)

@Serializable
private data class KitsuMappingsResponse(
    val data: List<KitsuMapping> = emptyList(),
)

@Serializable
private data class KitsuMapping(
    val relationships: KitsuMappingRelationships? = null,
)

@Serializable
private data class KitsuMappingRelationships(
    val item: KitsuRelationshipItem? = null,
)

@Serializable
private data class KitsuRelationshipItem(
    val data: KitsuRelationshipData? = null,
)

@Serializable
private data class KitsuRelationshipData(
    val id: String? = null,
)

@Serializable
private data class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
private data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
)
