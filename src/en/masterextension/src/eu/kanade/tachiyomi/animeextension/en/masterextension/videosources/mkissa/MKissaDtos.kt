package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun buildQuery(queryAction: () -> String): String = queryAction()
    .trimIndent()
    .replace("%", "$")

val STREAM_QUERY: String = buildQuery {
    """
        query(
            %showId: String!
            %translationType: VaildTranslationTypeEnumType!
            %episodeString: String!
        ) {
            episode(
                showId: %showId
                translationType: %translationType
                episodeString: %episodeString
            ) {
                sourceUrls
                show {
                    _id
                }
            }
        }
    """
}

val STREAM_HASH: String = MKissaCrypto.sha256Hex(STREAM_QUERY)

const val ANIME_LANE = "k7"

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
class AaApiError(
    val errors: List<GraphQlError>? = null,
) {
    @Serializable
    class GraphQlError(
        val message: String? = null,
        val extensions: Extensions? = null,
    ) {
        @Serializable
        class Extensions(
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
