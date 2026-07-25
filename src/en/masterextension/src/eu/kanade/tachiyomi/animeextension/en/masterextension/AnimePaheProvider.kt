package eu.kanade.tachiyomi.animeextension.en.masterextension

import android.util.Log
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
        private const val TAG = "AnimePaheProvider"
        // Official domains as of 2026: animepahe.pw, animepahe.com, animepahe.org
        private const val BASE_URL = "https://animepahe.pw"
        private val FALLBACK_DOMAINS = listOf("https://animepahe.com", "https://animepahe.org")
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // Cookie jar to persist DDoS-Guard session cookies (__ddg2_, etc.)
    private val cookieJar = PaheCookieJar()
    private val cookieClient by lazy {
        client.newBuilder()
            .cookieJar(cookieJar)
            .build()
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

    // =====================================================================
    // Session initialization (gets DDoS-Guard cookies)
    // =====================================================================
    private suspend fun initSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = cookieClient.newCall(
                Request.Builder().url(BASE_URL).headers(paheHeaders).get().build()
            ).execute()
            val success = response.isSuccessful
            Log.d(TAG, "initSession: HTTP ${response.code}, success=$success")
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "initSession failed", e)
            false
        }
    }

    // =====================================================================
    // Search: GET /api?m=search&l=8&q=$title
    // =====================================================================
    private suspend fun searchAnime(title: String): PaheSearchResult? = withContext(Dispatchers.IO) {
        initSession()

        val url = "$BASE_URL/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("l", "8")
            .addQueryParameter("q", title)
            .build()

        Log.d(TAG, "Searching: $url")

        val request = Request.Builder()
            .url(url)
            .headers(apiHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Search HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    Log.w(TAG, "Search returned empty body")
                    return@withContext null
                }
                Log.d(TAG, "Search response (first 200): ${body.take(200)}")
                val result = body.parseAs<PaheSearchResponse>().data?.firstOrNull()
                Log.d(TAG, "Search result: id=${result?.id}, title=${result?.title}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search exception", e)
            null
        }
    }

    // =====================================================================
    // Resolve anime session via redirect: GET /a/$animeId → extract session
    // =====================================================================
    private suspend fun resolveAnimeSession(animeId: Int): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/a/$animeId"
        Log.d(TAG, "Resolving session: $url")

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                // Follow redirect — the final URL contains the session
                val finalUrl = response.request.url.toString()
                Log.d(TAG, "Redirected to: $finalUrl")

                // URL format: https://animepahe.pw/anime/$session
                val session = finalUrl.substringAfterLast("/anime/").substringBefore("/")
                    .substringBefore("?")

                if (session.isNotBlank() && session != finalUrl) {
                    Log.d(TAG, "Resolved session: $session")
                    session
                } else {
                    // Fallback: try to extract from HTML
                    val html = response.body.string()
                    val doc = Jsoup.parse(html)
                    val link = doc.selectFirst("a[href*=/anime/]")?.attr("href")
                    val fromHtml = link?.substringAfterLast("/anime/")?.substringBefore("/")
                    Log.d(TAG, "Session from HTML fallback: $fromHtml")
                    fromHtml
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session resolution failed", e)
            null
        }
    }

    // =====================================================================
    // Get episode session: GET /api?m=release&id=$session&sort=episode_desc&page=$page
    // =====================================================================
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
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Release HTTP ${response.code}")
                        return@withContext null
                    }
                    val body = response.body.string()
                    val release = body.parseAs<PaheReleaseResponse>()
                    lastPage = release.last_page ?: 1

                    Log.d(TAG, "Release page $page/$lastPage, episodes: ${release.data?.size}")

                    val episode = release.data?.firstOrNull {
                        it.episode?.toInt() == epNum
                    }

                    if (episode != null) {
                        val session = episode.session
                        val audio = episode.audio ?: "jpn"
                        Log.d(TAG, "Found ep $epNum: session=$session, audio=$audio")
                        if (session != null) {
                            return@withContext session to audio
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Release fetch exception on page $page", e)
                return@withContext null
            }
            page++
        }
        Log.w(TAG, "Episode $epNum not found in $lastPage pages")
        null
    }

    // =====================================================================
    // Get video links from play page HTML
    // GET /play/$animeSession/$epSession → parse div#resolutionMenu > button[data-src]
    // =====================================================================
    private suspend fun getVideoLinksFromPlayPage(
        animeSession: String,
        epSession: String
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/play/$animeSession/$epSession"
        Log.d(TAG, "Play page: $url")

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Play page HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val html = response.body.string()
                val document = Jsoup.parse(html)

                // Primary: resolution menu buttons with data-src (kwik URLs)
                val buttons = document.select("div#resolutionMenu > button[data-src]")
                if (buttons.isNotEmpty()) {
                    val links = buttons.mapNotNull { btn ->
                        val src = btn.attr("data-src")
                        val quality = btn.text().trim()
                        if (src.isNotBlank()) {
                            Log.d(TAG, "  Button: quality='$quality', src='${src.take(60)}'")
                            src to quality
                        } else null
                    }
                    Log.d(TAG, "Found ${links.size} links from resolutionMenu")
                    return@withContext links
                }

                // Fallback: look for any data-src or data-href with kwik
                val fallbackLinks = document.select("[data-src*='kwik'], [data-href*='kwik']")
                    .mapNotNull { el ->
                        val src = el.attr("data-src").ifBlank { el.attr("data-href") }
                        val quality = el.text().trim().ifBlank { "unknown" }
                        if (src.isNotBlank()) src to quality else null
                    }

                if (fallbackLinks.isNotEmpty()) {
                    Log.d(TAG, "Found ${fallbackLinks.size} links from fallback selectors")
                    return@withContext fallbackLinks
                }

                // Last resort: regex for kwik URLs in the page
                val kwikRegex = Regex("""https://[^"'\s]*kwik[^"'\s]*""")
                val regexMatches = kwikRegex.findAll(html).map { it.value to "unknown" }.toList()
                Log.d(TAG, "Found ${regexMatches.size} links from regex")
                regexMatches
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play page fetch exception", e)
            emptyList()
        }
    }

    // =====================================================================
    // Kwik resolution — extract video URL from kwik page
    // =====================================================================
    private suspend fun resolveKwik(kwikUrl: String, quality: String): List<Video> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Resolving Kwik: $kwikUrl (quality: $quality)")

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
                if (!response.isSuccessful) {
                    Log.e(TAG, "Kwik HTTP ${response.code}")
                    return@withContext emptyList<Video>()
                }
                val html = response.body.string()

                // Method 1: Try to find HLS stream URL directly in page
                val hlsUrl = extractHlsFromKwik(html)
                if (hlsUrl != null) {
                    Log.d(TAG, "Kwik HLS found: $hlsUrl")
                    val videos = playlistUtils.extractFromHls(
                        hlsUrl,
                        kwikUrl,
                        kwikHeaders,
                        kwikHeaders,
                        videoNameGen = { res -> "$name $quality $res" }
                    )
                    if (videos.isNotEmpty()) return@withContext videos
                }

                // Method 2: Try packed JavaScript unpacking
                val unpackedUrl = extractFromPackedJs(html)
                if (unpackedUrl != null) {
                    Log.d(TAG, "Kwik unpacked URL: $unpackedUrl")
                    return@withContext when {
                        unpackedUrl.contains(".m3u8") -> {
                            playlistUtils.extractFromHls(
                                unpackedUrl,
                                kwikUrl,
                                kwikHeaders,
                                kwikHeaders,
                                videoNameGen = { res -> "$name $quality $res" }
                            )
                        }
                        else -> listOf(Video(unpackedUrl, "$name $quality", unpackedUrl, headers = kwikHeaders))
                    }
                }

                // Method 3: Regex fallback for any video URL
                val directUrl = Regex("""(https://[^"'\s]+\.mp4[^"'\s]*)""").find(html)
                if (directUrl != null) {
                    Log.d(TAG, "Kwik direct MP4: ${directUrl.value}")
                    return@withContext listOf(Video(directUrl.value, "$name $quality", directUrl.value, headers = kwikHeaders))
                }

                val m3u8Url = Regex("""(https://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)
                if (m3u8Url != null) {
                    Log.d(TAG, "Kwik regex M3U8: ${m3u8Url.value}")
                    return@withContext playlistUtils.extractFromHls(
                        m3u8Url.value,
                        kwikUrl,
                        kwikHeaders,
                        kwikHeaders,
                        videoNameGen = { res -> "$name $quality $res" }
                    )
                }

                Log.w(TAG, "Kwik: no video URL found. Page snippet: ${html.take(300)}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kwik resolution failed", e)
            emptyList()
        }
    }

    /**
     * Extract HLS URL from kwik page HTML.
     * Looks for source tags, video elements, or script-embedded URLs.
     */
    private fun extractHlsFromKwik(html: String): String? {
        // Check for <source> or <video> tags
        val doc = Jsoup.parse(html)
        val sourceTag = doc.selectFirst("source[src*='.m3u8']")?.attr("src")
        if (!sourceTag.isNullOrBlank()) return sourceTag

        val videoTag = doc.selectFirst("video[src*='.m3u8']")?.attr("src")
        if (!videoTag.isNullOrBlank()) return videoTag

        // Check for HLS URL in script tags
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        val hlsMatch = Regex("""["'](https://[^"']+\.m3u8[^"']*)["']""").find(scriptContent)
        return hlsMatch?.groupValues?.get(1)
    }

    /**
     * Extract video URL from packed JavaScript using JsUnpacker.
     */
    private fun extractFromPackedJs(html: String): String? {
        // Try JsUnpacker from the unpacker library
        val unpacked = JsUnpacker.unpackAndCombine(html)
        if (unpacked != null) {
            // Look for video URL in unpacked code
            val urlMatch = Regex("""["'](https://[^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(unpacked)
            if (urlMatch != null) return urlMatch.groupValues[1]

            // Look for src= pattern
            val srcMatch = Regex("""src\s*[=:]\s*["']([^"']+)["']""").find(unpacked)
            if (srcMatch != null) return srcMatch.groupValues[1]
        }

        // Manual fallback: try the classic eval(function(p,a,c,k,e,d)) pattern
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

        val urlRegex = Regex("""src=["']([^"']+)["']""")
        return urlRegex.find(result)?.groupValues?.get(1)
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

    // =====================================================================
    // MAIN VIDEO FETCH
    // =====================================================================
    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) {
            Log.e(TAG, "Title is blank, aborting")
            return emptyList()
        }

        Log.d(TAG, "fetchVideos: '$title' ep ${meta.epNum}")

        // Step 1: Search for the anime
        val searchResult = searchAnime(title)
        if (searchResult == null) {
            Log.e(TAG, "Search returned null for '$title'")
            return emptyList()
        }
        val animeId = searchResult.id
        if (animeId == null) {
            Log.e(TAG, "Search result has no id")
            return emptyList()
        }

        // Step 2: Resolve anime session via redirect
        val animeSession = resolveAnimeSession(animeId)
        if (animeSession == null) {
            Log.e(TAG, "Could not resolve anime session for id=$animeId")
            return emptyList()
        }

        // Step 3: Find the episode session
        val (epSession, audio) = getEpisodeSession(animeSession, meta.epNum)
            ?: return emptyList()

        // Step 4: Get video links from play page
        val videoLinks = getVideoLinksFromPlayPage(animeSession, epSession)
        if (videoLinks.isEmpty()) {
            Log.w(TAG, "No video links found on play page")
            return emptyList()
        }

        // Step 5: Resolve each kwik link
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

        Log.d(TAG, "Total videos: ${videos.size}")
        return videos
    }
}
