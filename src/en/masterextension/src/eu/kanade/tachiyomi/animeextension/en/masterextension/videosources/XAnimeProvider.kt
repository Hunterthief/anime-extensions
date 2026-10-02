package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.xanime.Crypto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.xanime.Queries
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.xanime.SearchSelect
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

class XAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {
    override val name = "XAnime"
    override val baseUrl = "https://xanime.me"

    private val cryptoClient by lazy {
        client.newBuilder().addInterceptor(Crypto()).build()
    }

    private val api by lazy { Queries(cryptoClient, { baseUrl }, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        return try {
            val meta = EpisodeMeta.from(episode)
            val title = anime.title.takeIf { it.isNotBlank() } ?: meta.title
            if (title.isBlank()) return emptyList()

            // 1. Search via GraphQL
            val searchSelect = SearchSelect(word = title, sortby = "field_score", page = 1)
            val searchRes = api.searchAnime(searchSelect)
            val searchItems = searchRes.searchData?.items ?: return emptyList()
            
            val targetItem = searchItems.firstOrNull { 
                it.toSAnime(baseUrl).title.equals(title, ignoreCase = true) 
            } ?: searchItems.firstOrNull() ?: return emptyList()
            
            val aniId = targetItem.toSAnime(baseUrl).url

            // 2. Get Episodes
            val epRes = api.getEpisodes(aniId, 1)
            val episodes = epRes.episodesData?.items ?: return emptyList()
            
            val targetEp = episodes.firstOrNull { 
                it.toSEpisode().episode_number.toInt() == meta.epNum 
            } ?: episodes.getOrNull(meta.epNum - 1) ?: return emptyList()
            
            val epId = targetEp.epId

            // 3. Extract HLS Streams
            val videoRes = api.getVideoUrl(epId)
            val sources = videoRes.videoUrlData?.data?.sourcesList ?: return emptyList()

            sources.parallelCatchingFlatMapBlocking { source ->
                val srcData = source.data ?: return@parallelCatchingFlatMapBlocking emptyList()
                val srcType = srcData.srcType?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Unknown"
                val serverName = srcData.srcName ?: "Unknown"

                val trackList = srcData.tracks.mapNotNull { track ->
                    track.trackPath?.let { path ->
                        val url = if (path.startsWith("http")) path else baseUrl + path
                        Track(url, track.label ?: "Unknown")
                    }
                }

                buildList {
                    srcData.souPath?.let { path ->
                        val url = if (path.startsWith("http")) path else baseUrl + path
                        addAll(playlistUtils.extractFromHls(url, videoNameGen = { q -> "$srcType - $serverName: $q" }, subtitleList = trackList))
                    }
                    srcData.m3u8Lists.forEach { m3u8 ->
                        m3u8.iframe?.let { path ->
                            val url = if (path.startsWith("http")) path else baseUrl + path
                            addAll(playlistUtils.extractFromHls(url, videoNameGen = { q -> "$srcType - ${m3u8.name ?: "Unknown"}: $q" }, subtitleList = trackList))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
