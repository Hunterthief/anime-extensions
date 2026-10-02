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
class SearchResult(
    val data: SearchResultData,
) {
    @Serializable
    class SearchResultData(
        val shows: SearchResultShows,
    ) {
        @Serializable
        class SearchResultShows(
            val edges: List<ShowCard>,
        )
    }
}

@Serializable
class ShowCard(
    @SerialName("_id") val id: String,
    val name: String,
    val thumbnail: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val slugTime: String? = null,
)

@Serializable
class SeriesResult(
    val data: DataShow,
) {
    @Serializable
    class DataShow(
        val show: SeriesShows,
    ) {
        @Serializable
        class SeriesShows(
            @SerialName("_id") val id: String,
            val availableEpisodesDetail: AvailableEps,
        ) {
            @Serializable
            class AvailableEps(
                val sub: List<String>? = null,
                val dub: List<String>? = null,
            )
        }
    }
}

@Serializable
class EpisodeResult(
    val data: DataEpisode,
) {
    @Serializable
    class DataEpisode(
        val episode: Episode? = null,
    )
}

@Serializable
class Episode(
    val sourceUrls: List<SourceUrl>,
) {
    @Serializable
    class SourceUrl(
        val sourceUrl: String,
        val type: String,
        val sourceName: String,
        val priority: Float = 0F,
    )
}

@Serializable
class EncryptedEpisodeResult(
    val data: EncryptedData,
) {
    @Serializable
    class EncryptedData(
        val tobeparsed: String? = null,
    )
}

@Serializable
class DecryptedEpisodeResult(
    val episode: Episode? = null,
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
class AaCryptoBootstrap(
    val epoch: Long,
    val partB: String,
    val k: String? = null,
    val switchAt: Long? = null,
)

@Serializable
class AaReqPayload(
    private val v: Int,
    private val ts: Long,
    private val epoch: Long,
    private val buildId: String,
    private val qh: String,
    private val k: String,
)
