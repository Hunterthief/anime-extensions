package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.en.masterextension.EpisodeMeta
import eu.kanade.tachiyomi.animeextension.en.masterextension.VideoProvider
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.BootstrapDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.CatalogResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.EmbedSessionDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.SeriesResponseDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.SettlarProxy
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.SettlarSessionDto
import eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anipm.fmtNum
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.nanohttpd.protocols.http.NanoHTTPD
import java.net.URLEncoder

class AniPMProvider(
    private val client: OkHttpClient,
    private val headers: Headers,
) : VideoProvider {

    override val name = "AniPM"
    override val baseUrl = "https://ani.pm"
    
    private val apiUrl = "$baseUrl/api"
    private val embedApi = "https://embed.settlar.io"
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    
    private val apiHeaders: Headers by lazy {
        headers.newBuilder().set("Accept", "application/json, text/plain, */*").set("Referer", "$baseUrl/anime").build()
    }
    private val embedHeaders: Headers by lazy {
        headers.newBuilder().set("Accept", "application/json").set("Referer", "$baseUrl/").build()
    }

    @Volatile private var proxy: SettlarProxy? = null
    @Synchronized
    private fun getProxy(): SettlarProxy {
        proxy?.takeIf { it.isAlive }?.let { return it }
        proxy?.stop()
        return SettlarProxy(headers).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); proxy = it }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: EpisodeMeta): List<Video> {
        val searchQuery = episode.title.ifBlank { anime.title }
        val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
        val searchUrl = "$apiUrl/anime/catalog?q=$encodedQuery&limit=10"
        
        val catalog = try {
            client.newCall(GET(searchUrl, apiHeaders)).awaitSuccess().parseAs<CatalogResponseDto>()
        } catch (_: Exception) { return emptyList() }
        
        val seriesItem = catalog.items.firstOrNull() ?: return emptyList()
        val settlarId = seriesItem.id ?: return emptyList()
        
        val series = try {
            client.newCall(GET("$apiUrl/anime/series/$settlarId?routes=e3", apiHeaders)).awaitSuccess().parseAs<SeriesResponseDto>()
        } catch (_: Exception) { return emptyList() }
        
        val epNumStr = episode.epNum.toString()
        val targetEp = series.episodes.firstOrNull { fmtNum(it.number) == epNumStr } ?: return emptyList()
        val epParam = targetEp.routeId ?: epNumStr
        
        val videos = mutableListOf<Video>()
        
        suspend fun fetchLangVideos(lang: String, langLabel: String) {
            try {
                val boot = client.newCall(GET("$apiUrl/anime/playback-bootstrap/settlar/$settlarId?ep=$epParam&lang=$lang", apiHeaders)).awaitSuccess().parseAs<BootstrapDto>()
                val selection = boot.settlarSelection ?: return
                
                val sessionUrl = "$apiUrl/anime/settlar/session".toHttpUrl().newBuilder()
                    .addQueryParameter("selection", selection).addQueryParameter("provider", "anipm")
                    .addQueryParameter("ep", epParam).addQueryParameter("channel", lang).addQueryParameter("telemetry", "0").build()
                
                val settlar = client.newCall(GET(sessionUrl, apiHeaders)).awaitSuccess().parseAs<SettlarSessionDto>()
                val token = settlar.embedUrl?.toHttpUrl()?.queryParameter("t") ?: return
                
                val embed = client.newCall(GET("$embedApi/api/embed/session?t=$token", embedHeaders)).awaitSuccess().parseAs<EmbedSessionDto>()
                val manifest = embed.source ?: return
                
                val proxy = getProxy()
                val subtitleTracks = embed.subtitles.mapNotNull { sub ->
                    val subUrl = sub.url ?: return@mapNotNull null
                    Track(proxy.subtitleUrl(subUrl), sub.label ?: sub.srclang ?: "Unknown")
                }
                
                val extractedVideos = playlistUtils.extractFromHls(
                    playlistUrl = proxy.proxyUrl(manifest), referer = embedApi,
                    masterHeaders = headers, videoHeaders = headers, subtitleList = subtitleTracks
                )
                
                extractedVideos.forEach { vid ->
                    videos.add(vid.copy(videoTitle = "AniPM - ${vid.videoTitle} [$langLabel]"))
                }
            } catch (_: Exception) { /* Ignore missing languages */ }
        }
        
        if (targetEp.sub) fetchLangVideos("sub", "Sub")
        if (targetEp.dub) fetchLangVideos("dub", "Dub")
        if (targetEp.subhard) fetchLangVideos("subhard", "Hard Sub")
        if (targetEp.dubhard) fetchLangVideos("dubhard", "Hard Dub")
        
        return videos
    }
}
