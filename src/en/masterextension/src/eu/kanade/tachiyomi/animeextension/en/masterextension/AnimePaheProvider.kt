package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AnimePahe"

    companion object {
        private const val BASE_URL = "https://animepahe.pw"
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val cookieJar = PaheCookieJar()
    private val cookieClient by lazy {
        client.newBuilder().cookieJar(cookieJar).build()
    }

    private class PaheCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
            this.cookies.addAll(cookies)
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies.filter { it.matches(url) }
        }
    }

    private val paheHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
    }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private suspend fun initSession() {
        runCatching {
            cookieClient.newCall(GET(BASE_URL, paheHeaders)).awaitSuccess().close()
        }
    }

    private suspend fun searchAnime(title: String): PaheSearchResult? {
        initSession()

        val url = "$BASE_URL/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("l", "8")
            .addQueryParameter("q", title)
            .build().toString()

        val body = cookieClient.newCall(GET(url, apiHeaders)).awaitSuccess().bodyString()
        if (body.isBlank()) return null
        return body.parseAs<PaheSearchResponse>().data?.firstOrNull()
    }

    private suspend fun resolveAnimeSession(animeId: Int): String? {
        val url = "$BASE_URL/a/$animeId"
        val response = cookieClient.newCall(GET(url, paheHeaders)).awaitSuccess()
        val finalUrl = response.request.url.toString()
        response.close()

        val session = finalUrl.substringAfterLast("/anime/").substringBefore("/").substringBefore("?")
        if (session.isNotBlank() && session != finalUrl) return session

        // Fallback: fetch page and look for link
        val html = cookieClient.newCall(GET(url, paheHeaders)).awaitSuccess().bodyString()
        val doc = Jsoup.parse(html)
        return doc.selectFirst("a[href*=/anime/]")?.attr("href")
            ?.substringAfterLast("/anime/")?.substringBefore("/")
    }

    private suspend fun getEpisodeSession(
        animeSession: String,
        epNum: Int
    ): Pair<String, String>? {
        var page = 1
        var lastPage = 1

        while (page <= lastPage) {
            val url = "$BASE_URL/api".toHttpUrl().newBuilder()
                .addQueryParameter("m", "release")
                .addQueryParameter("id", animeSession)
                .addQueryParameter("sort", "episode_desc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("l", "30")
                .build().toString()

            val body = cookieClient.newCall(GET(url, apiHeaders)).awaitSuccess().bodyString()
            val release = body.parseAs<PaheReleaseResponse>()
            lastPage = release.last_page ?: 1

            val episode = release.data?.firstOrNull { it.episode?.toInt() == epNum }
            if (episode != null) {
                val session = episode.session ?: return null
                return session to (episode.audio ?: "jpn")
            }
            page++
        }
        return null
    }

    private suspend fun getVideoLinksFromPlayPage(
        animeSession: String,
        epSession: String
    ): List<Pair<String, String>> {
        val url = "$BASE_URL/play/$animeSession/$epSession"
        val html = cookieClient.newCall(GET(url, paheHeaders)).awaitSuccess().bodyString()
        val document = Jsoup.parse(html)

        // Primary: resolution menu buttons
        val buttons = document.select("div#resolutionMenu > button[data-src]")
        if (buttons.isNotEmpty()) {
            return buttons.mapNotNull { btn ->
                val src = btn.attr("data-src")
                val quality = btn.text().trim()
                if (src.isNotBlank()) src to quality else null
            }
        }

        // Fallback: any element with kwik data-src
        val fallbackLinks = document.select("[data-src*='kwik'], [data-href*='kwik']")
            .mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("data-href") }
                val quality = el.text().trim().ifBlank { "unknown" }
                if (src.isNotBlank()) src to quality else null
            }
        if (fallbackLinks.isNotEmpty()) return fallbackLinks

        // Last resort: regex
        return Regex("""https://[^"'\s]*kwik[^"'\s]*""")
            .findAll(html).map { it.value to "unknown" }.toList()
    }

    private suspend fun resolveKwik(kwikUrl: String, quality: String): List<Video> {
        val kwikHeaders = headers.newBuilder()
            .set("Referer", "$BASE_URL/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val html = cookieClient.newCall(GET(kwikUrl, kwikHeaders)).awaitSuccess().bodyString()

        // Method 1: Direct HLS URL in page
        val hlsUrl = extractHlsFromKwik(html)
        if (hlsUrl != null) {
            val videos = playlistUtils.extractFromHls(
                hlsUrl,
                masterHeaders = kwikHeaders,
                videoHeaders = kwikHeaders,
                videoNameGen = { res -> "$name $quality $res" }
            )
            if (videos.isNotEmpty()) return videos
        }

        // Method 2: Packed JS
        val unpackedUrl = extractFromPackedJs(html)
        if (unpackedUrl != null) {
            return when {
                unpackedUrl.contains(".m3u8") -> playlistUtils.extractFromHls(
                    unpackedUrl,
                    masterHeaders = kwikHeaders,
                    videoHeaders = kwikHeaders,
                    videoNameGen = { res -> "$name $quality $res" }
                )
                else -> listOf(Video(unpackedUrl, "$name $quality", unpackedUrl, headers = kwikHeaders))
            }
        }

        // Method 3: Regex fallback
        val directUrl = Regex("""(https://[^"'\s]+\.mp4[^"'\s]*)""").find(html)
        if (directUrl != null) {
            return listOf(Video(directUrl.value, "$name $quality", directUrl.value, headers = kwikHeaders))
        }

        val m3u8Url = Regex("""(https://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)
        if (m3u8Url != null) {
            return playlistUtils.extractFromHls(
                m3u8Url.value,
                masterHeaders = kwikHeaders,
                videoHeaders = kwikHeaders,
                videoNameGen = { res -> "$name $quality $res" }
            )
        }

        return emptyList()
    }

    private fun extractHlsFromKwik(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("source[src*='.m3u8']")?.attr("src")?.let {
            if (it.isNotBlank()) return it
        }
        doc.selectFirst("video[src*='.m3u8']")?.attr("src")?.let {
            if (it.isNotBlank()) return it
        }
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""["'](https://[^"']+\.m3u8[^"']*)["']""")
            .find(scriptContent)?.groupValues?.get(1)
    }

    private fun extractFromPackedJs(html: String): String? {
        val unpacked = JsUnpacker.unpackAndCombine(html)
        if (unpacked != null) {
            Regex("""["'](https://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                .find(unpacked)?.let { return it.groupValues[1] }
            Regex("""src\s*[=:]\s*["']([^"']+)["']""")
                .find(unpacked)?.let { return it.groupValues[1] }
        }

        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = packedRegex.find(html) ?: return null

        val p = match.groupValues[1]
        val a = match.groupValues[2].toIntOrNull() ?: return null
        val c = match.groupValues[3].toIntOrNull() ?: return null
        val k = match.groupValues[4].split("|")
        if (k.size != c) return null

        val d = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val key = toBase(i, a)
            d[key] = k.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
        }

        var result = p
        for (i in c - 1 downTo 0) {
            val key = toBase(i, a)
            val replacement = d[key]
            if (replacement != null && replacement.isNotEmpty() && key != replacement) {
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), replacement)
            }
        }

        result = result.replace("\\/", "/").replace("\\'", "'").replace("\\\"", "\"")
        return Regex("""src=["']([^"']+)["']""").find(result)?.groupValues?.get(1)
    }

    private fun toBase(num: Int, base: Int): String {
        if (base <= 36) return num.toString(base)
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, chars[n % base]); n /= base }
        return sb.toString()
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val searchResult = searchAnime(title) ?: return emptyList()
        val animeId = searchResult.id ?: return emptyList()

        val animeSession = resolveAnimeSession(animeId) ?: return emptyList()

        val (epSession, audio) = getEpisodeSession(animeSession, meta.epNum) ?: return emptyList()

        val videoLinks = getVideoLinksFromPlayPage(animeSession, epSession)
        if (videoLinks.isEmpty()) return emptyList()

        val audioLabel = if (audio == "eng") "Dub" else "Sub"

        return videoLinks.parallelCatchingFlatMap { (kwikUrl, quality) ->
            resolveKwik(kwikUrl, quality).map { v ->
                Video(
                    v.url,
                    "$name $quality $audioLabel ${v.quality}",
                    v.videoUrl ?: v.url,
                    headers = v.headers
                )
            }
        }
    }
}
