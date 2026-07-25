package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AnimePahe"

    companion object {
        // Official domains (2026): animepahe.pw, animepahe.com, animepahe.org
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
        Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            add("Referer", "$BASE_URL/")
        }.build()
    }

    private val apiHeaders by lazy {
        Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", "$BASE_URL/")
        }.build()
    }

    private suspend fun initSession() = withContext(Dispatchers.IO) {
        try {
            cookieClient.newCall(
                Request.Builder().url(BASE_URL).headers(paheHeaders).get().build()
            ).execute().close()
        } catch (_: Exception) { }
    }

    private suspend fun searchAnime(title: String): PaheSearchResult? = withContext(Dispatchers.IO) {
        initSession()

        val url = "$BASE_URL/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("l", "8")
            .addQueryParameter("q", title)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(apiHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                if (body.isBlank()) return@withContext null
                body.parseAs<PaheSearchResponse>().data?.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveAnimeSession(animeId: Int): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/a/$animeId"

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val session = finalUrl.substringAfterLast("/anime/").substringBefore("/").substringBefore("?")

                if (session.isNotBlank() && session != finalUrl) {
                    session
                } else {
                    val html = response.body.string()
                    val doc = Jsoup.parse(html)
                    doc.selectFirst("a[href*=/anime/]")?.attr("href")
                        ?.substringAfterLast("/anime/")?.substringBefore("/")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getEpisodeSession(
        animeSession: String,
        epNum: Int
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        var page = 1
        var lastPage = 1

        while (page <= lastPage) {
            val url = "$BASE_URL/api".toHttpUrl().newBuilder()
                .addQueryParameter("m", "release")
                .addQueryParameter("id", animeSession)
                .addQueryParameter("sort", "episode_desc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("l", "30")
                .build()

            val request = Request.Builder()
                .url(url)
                .headers(apiHeaders)
                .get()
                .build()

            try {
                cookieClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body.string()
                    val release = body.parseAs<PaheReleaseResponse>()
                    lastPage = release.last_page ?: 1

                    val episode = release.data?.firstOrNull {
                        it.episode?.toInt() == epNum
                    }

                    if (episode != null) {
                        val session = episode.session ?: return@withContext null
                        return@withContext session to (episode.audio ?: "jpn")
                    }
                }
            } catch (_: Exception) {
                return@withContext null
            }
            page++
        }
        null
    }

    private suspend fun getVideoLinksFromPlayPage(
        animeSession: String,
        epSession: String
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/play/$animeSession/$epSession"

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body.string()
                val document = Jsoup.parse(html)

                // Primary: resolution menu buttons
                val buttons = document.select("div#resolutionMenu > button[data-src]")
                if (buttons.isNotEmpty()) {
                    return@withContext buttons.mapNotNull { btn ->
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

                if (fallbackLinks.isNotEmpty()) return@withContext fallbackLinks

                // Last resort: regex
                val kwikRegex = Regex("""https://[^"'\s]*kwik[^"'\s]*""")
                kwikRegex.findAll(html).map { it.value to "unknown" }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveKwik(kwikUrl: String, quality: String): List<Video> = withContext(Dispatchers.IO) {
        val kwikHeaders = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Referer", "$BASE_URL/")
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.build()

        val request = Request.Builder()
            .url(kwikUrl)
            .headers(kwikHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList<Video>()
                val html = response.body.string()

                // Method 1: Direct HLS URL in page
                val hlsUrl = extractHlsFromKwik(html)
                if (hlsUrl != null) {
                    val videos = playlistUtils.extractFromHls(
                        hlsUrl, kwikUrl, kwikHeaders, kwikHeaders,
                        videoNameGen = { res -> "$name $quality $res" }
                    )
                    if (videos.isNotEmpty()) return@withContext videos
                }

                // Method 2: Packed JS unpacking
                val unpackedUrl = extractFromPackedJs(html)
                if (unpackedUrl != null) {
                    return@withContext when {
                        unpackedUrl.contains(".m3u8") -> {
                            playlistUtils.extractFromHls(
                                unpackedUrl, kwikUrl, kwikHeaders, kwikHeaders,
                                videoNameGen = { res -> "$name $quality $res" }
                            )
                        }
                        else -> listOf(Video(unpackedUrl, "$name $quality", unpackedUrl, headers = kwikHeaders))
                    }
                }

                // Method 3: Regex fallback
                val directUrl = Regex("""(https://[^"'\s]+\.mp4[^"'\s]*)""").find(html)
                if (directUrl != null) {
                    return@withContext listOf(Video(directUrl.value, "$name $quality", directUrl.value, headers = kwikHeaders))
                }

                val m3u8Url = Regex("""(https://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)
                if (m3u8Url != null) {
                    return@withContext playlistUtils.extractFromHls(
                        m3u8Url.value, kwikUrl, kwikHeaders, kwikHeaders,
                        videoNameGen = { res -> "$name $quality $res" }
                    )
                }

                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
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
        val hlsMatch = Regex("""["'](https://[^"']+\.m3u8[^"']*)["']""").find(scriptContent)
        return hlsMatch?.groupValues?.get(1)
    }

    private fun extractFromPackedJs(html: String): String? {
        // Try library JsUnpacker first
        val unpacked = JsUnpacker.unpackAndCombine(html)
        if (unpacked != null) {
            val urlMatch = Regex("""["'](https://[^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(unpacked)
            if (urlMatch != null) return urlMatch.groupValues[1]

            val srcMatch = Regex("""src\s*[=:]\s*["']([^"']+)["']""").find(unpacked)
            if (srcMatch != null) return srcMatch.groupValues[1]
        }

        // Manual fallback for classic packed JS
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
        while (n > 0) {
            sb.insert(0, chars[n % base])
            n /= base
        }
        return sb.toString()
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val searchResult = searchAnime(title) ?: return emptyList()
        val animeId = searchResult.id ?: return emptyList()

        val animeSession = resolveAnimeSession(animeId) ?: return emptyList()

        val (epSession, audio) = getEpisodeSession(animeSession, meta.epNum)
            ?: return emptyList()

        val videoLinks = getVideoLinksFromPlayPage(animeSession, epSession)
        if (videoLinks.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()
        val audioLabel = if (audio == "eng") "Dub" else "Sub"

        for ((kwikUrl, quality) in videoLinks) {
            val kwikVideos = resolveKwik(kwikUrl, quality)
            for (v in kwikVideos) {
                videos.add(
                    Video(
                        v.url,
                        "$name $quality $audioLabel ${v.quality}",
                        v.videoUrl ?: v.url,
                        headers = v.headers
                    )
                )
            }
        }

        return videos
    }
}
