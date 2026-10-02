package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.xanime

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

class Queries(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val headers: Headers,
) {
    private val searchQuery = """
        query get_q27(${'$'}select: SearchAnime_Select) {
          get_q27(select: ${'$'}select) {
            paging { total pages page next prev }
            items { id data { ani_id info_title info_slug urlCover600 urlCoverOri } }
          }
        }
    """

    private val episodesQuery = """
        query get_q01(${'$'}select: AnimesEpisodesList_Select) {
            get_q01(select: ${'$'}select){
                paging { total pages page next prev }
                items { id data {
                    ani_id ep_id ep_index ep_title epPath date_create date_update ep_sub_index
                    sourcesNode_list { id data { sou_id src_type } }
                } }
            }
        }
    """

    private val videoUrlQuery = """
        query get_q07(${'$'}select: Episodes_Select) {
            get_q07(select: ${'$'}select) {
                id data {
                    epPath
                    sourcesNode_list {
                        id data {
                            sou_id src_name src_type souPath
                            m3u8_lists { name iframe }
                            track { label kind default local trackPath }
                        }
                    }
                }
            }
        }
    """

    suspend fun searchAnime(select: SearchSelect): SearchResponse {
        val payload = GraphQlPayload(query = searchQuery, variables = SearchVariables(select).toJsonElement())
        return executeRequest(payload)
    }

    suspend fun getEpisodes(aniId: String, page: Int = 1): EpisodesResponse {
        val payload = GraphQlPayload(query = episodesQuery, variables = EpisodeVariables(EpisodeSelect(aniId, page = page)).toJsonElement())
        return executeRequest(payload)
    }

    suspend fun getVideoUrl(epId: String): VideoUrlResponse {
        val payload = GraphQlPayload(query = videoUrlQuery, variables = VideoVariables(VideoSelect(epId)).toJsonElement())
        return executeRequest(payload)
    }

    private suspend inline fun <reified T> executeRequest(payload: GraphQlPayload): T {
        val request = POST("${baseUrlProvider()}/z2/", headers = headers, body = payload.toJsonRequestBody())
        return client.newCall(request).awaitSuccess().parseAs<GraphQlResponse<T>>().data
            ?: throw Exception("Unexpected API response")
    }
}

// ======================== DTOs ========================
@Serializable class GraphQlPayload(private val query: String, private val variables: JsonElement)
@Serializable class GraphQlResponse<T>(val data: T? = null)
@Serializable class SearchResponse(@SerialName("get_q27") val searchData: SearchData? = null)
@Serializable class SearchData(val items: List<SearchItem> = emptyList())
@Serializable class SearchItem(val id: String? = null, private val data: AnimeData? = null) {
    fun toSAnime(baseUrl: String) = data?.toSAnime(baseUrl) ?: throw Exception("Missing data")
}
@Serializable class AnimeData(@SerialName("ani_id") private val aniId: String, @SerialName("info_title") private val title: String? = null) {
    fun toSAnime(baseUrl: String) = eu.kanade.tachiyomi.animesource.model.SAnime.create().apply {
        url = aniId
        title = this@AnimeData.title ?: "Unknown"
    }
}
@Serializable class SearchVariables(private val select: SearchSelect)
@Serializable class SearchSelect(private val word: String, private val sortby: String, private val page: Int)
@Serializable class EpisodesResponse(@SerialName("get_q01") val episodesData: EpisodesData? = null)
@Serializable class EpisodesData(val items: List<EpisodeItem> = emptyList())
@Serializable class EpisodeItem(private val data: EpisodeData) {
    val epId: String get() = data.epId
    fun toSEpisode() = data.toSEpisode()
}
@Serializable class EpisodeData(@SerialName("ani_id") private val aniId: String, @SerialName("ep_id") val epId: String, @SerialName("ep_index") private val index: Int = 0) {
    fun toSEpisode() = eu.kanade.tachiyomi.animesource.model.SEpisode.create().apply {
        url = "$aniId/$epId"
        episode_number = index.toFloat()
    }
}
@Serializable class EpisodeVariables(private val select: EpisodeSelect)
@Serializable class EpisodeSelect(@SerialName("ani_id") private val aniId: String, private val page: Int = 1)
@Serializable class VideoUrlResponse(@SerialName("get_q07") val videoUrlData: VideoUrlData? = null)
@Serializable class VideoUrlData(val data: EpisodeSourcesData? = null)
@Serializable class EpisodeSourcesData(@SerialName("sourcesNode_list") val sourcesList: List<SourceNode> = emptyList())
@Serializable class SourceNode(val data: SourceData? = null)
@Serializable class SourceData(@SerialName("src_name") val srcName: String? = null, @SerialName("src_type") val srcType: String? = null, val souPath: String? = null, @SerialName("m3u8_lists") val m3u8Lists: List<M3u8List> = emptyList(), @SerialName("track") val tracks: List<TrackData> = emptyList())
@Serializable class TrackData(val label: String? = null, val trackPath: String? = null)
@Serializable class M3u8List(val name: String? = null, val iframe: String? = null)
@Serializable class VideoVariables(private val select: VideoSelect)
@Serializable class VideoSelect(val id: String)
