package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animenosub

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class WolfstreamExtractor(private val client: OkHttpClient) {
    private val sourcesRegex by lazy { Regex("""sources\s*:\s*(.+?]),""", RegexOption.DOT_MATCHES_ALL) }
    private val urlsRegex by lazy { Regex("""file\s*:\s*["']([^"']+)["']""") }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val doc = try { client.newCall(GET(url)).awaitSuccess().asJsoup() } catch (_: Exception) { return emptyList() }
        val unpacked = doc.selectFirst("script:containsData(sources)")?.data() ?: return emptyList()
        val sources = sourcesRegex.find(unpacked)?.groupValues?.getOrNull(1) ?: return emptyList()
        
        val urls = urlsRegex.findAll(sources).mapNotNull { match -> match.groupValues[1].takeIf { it.isNotBlank() } }.toList()
        return urls.map { videoUrl -> Video(videoUrl, "${prefix}WolfStream", videoUrl) }
    }
}
