package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val STREAM_HASH = "f4662f4b7510b26795dd53ef824a0bf1740fbbc5d1273fab18222ac831bca8d0"
const val ANIME_LANE = "k7"

fun buildQuery(queryAction: () -> String): String = queryAction()
    .trimIndent()
    .replace("%", "$")

val SEARCH_QUERY: String = buildQuery {
    """
        query(
            %search: SearchInput
            %limit: Int
            %page: Int
            %translationType: VaildTranslationTypeEnumType
            %countryOrigin: VaildCountryOriginEnumType
        ) {
            shows(
                search: %search
                limit: %limit
                page: %page
                translationType: %translationType
                countryOrigin: %countryOrigin
            ) {
                pageInfo {
                    total
                }
                edges {
                    _id
                    name
                    thumbnail
                    englishName
                    nativeName
                    slugTime
                }
            }
        }
    """
}

val EPISODES_QUERY = buildQuery {
    """
        query (%_id: String!) {
            show(
                _id: %_id
            ) {
                _id
                availableEpisodesDetail
            }
        }
    """
}

@Serializable
data class MKissaSearchResult(
    val data: MKissaSearchData,
) {
    @Serializable
    data class MKissaSearchData(
        val shows: MKissaSearchShows,
    ) {
        @Serializable
        data class MKissaSearchShows(
            val edges: List<MKissaSearchEdge>,
        ) {
            @Serializable
            data class MKissaSearchEdge(
                @SerialName("_id")
                val id: String,
                val name: String,
                val englishName: String? = null,
                val nativeName: String? = null,
            )
        }
    }
}

@Serializable
data class MKissaSeriesResult(
    val data: MKissaSeriesData,
) {
    @Serializable
    data class MKissaSeriesData(
        val show: MKissaSeriesShow,
    ) {
        @Serializable
        data class MKissaSeriesShow(
            @SerialName("_id")
            val id: String,
            val availableEpisodesDetail: MKissaAvailableEps,
        ) {
            @Serializable
            data class MKissaAvailableEps(
                val sub: List<String>? = null,
                val dub: List<String>? = null,
            )
        }
    }
}

@Serializable
data class MKissaEpisodeResult(
    val data: MKissaEpisodeData,
) {
    @Serializable
    data class MKissaEpisodeData(
        val episode: MKissaEpisode? = null,
    ) {
        @Serializable
        data class MKissaEpisode(
            val sourceUrls: List<MKissaSourceUrl>,
        )
    }
}

@Serializable
data class MKissaSourceUrl(
    val sourceUrl: String,
    val type: String,
    val sourceName: String,
    val priority: Float = 0F,
)

@Serializable
data class MKissaEncryptedResult(
    val data: MKissaEncryptedData,
) {
    @Serializable
    data class MKissaEncryptedData(
        val tobeparsed: String? = null,
    )
}

@Serializable
data class MKissaDecryptedResult(
    val episode: MKissaEpisodeResult.MKissaEpisodeData.MKissaEpisode? = null,
)

@Serializable
class MKissaApiError(
    val errors: List<MKissaGraphQlError>? = null,
) {
    @Serializable
    class MKissaGraphQlError(
        val extensions: MKissaErrorExtensions? = null,
    ) {
        @Serializable
        class MKissaErrorExtensions(
            val code: String? = null,
        )
    }
}

@Serializable
class MKissaCryptoBootstrap(
    val epoch: Long,
    val partB: String,
    val k: String? = null,
)

@Serializable
class MKissaAaReqPayload(
    private val v: Int,
    private val ts: Long,
    private val epoch: Long,
    private val buildId: String,
    private val qh: String,
    private val k: String,
)
