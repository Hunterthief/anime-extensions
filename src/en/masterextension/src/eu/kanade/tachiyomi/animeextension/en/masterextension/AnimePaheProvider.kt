package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class AnimePaheProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AnimePahe"

    private val baseUrl = "https://animepahe.ru"
    private val apiUrl = "https://animepahe.ru"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val cookieJar = SimpleCookieJar()

    private val cookieClient by lazy {
        client.newBuilder().cookieJar(cookieJar).build()
    }

    private class SimpleCookieJar : CookieJar {
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
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", "$baseUrl/")
        }.build()
    }

    @Serializable
    data class PaheSearchResponse(
        val total: Int? = null,
        val data: List<PaheSearchResult>? = null
    )

    @Serializable
    data class PaheSearchResult(
        val id: Int? = null,
        val session: String? = null,
        val title: String? = null
    )

    @Serializable
    data class PaheReleaseResponse(
        val total: Int? = null,
        val last_page: Int? = null,
        val data: List<PaheReleaseEpisode>? = null
    )

    @Serializable
    data class PaheReleaseEpisode(
        val session: String? = null,
        val episode: Double? = null,
        val audio: String? = null
    )

    @Serializable
    data class PaheLinksResponse(
        val data: List<PaheLinkData>? = null
    )

    @Serializable
    data class PaheLinkData(
        val quality: String? = null,
        val audio: String? = null,
        val kwik: String? = null
    )

    private suspend fun initSession() = withContext(Dispatchers.IO) {
        try {
            cookieClient.newCall(
                Request.Builder().url(baseUrl).headers(paheHeaders).get().build()
            ).execute().close()
        } catch (_: Exception) { }
    }

    private suspend fun searchAnime(title: String): PaheSearchResult? = withContext(Dispatchers.IO) {
        initSession()

        val url = "$apiUrl/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "search")
            .addQueryParameter("q", title)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
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

    private suspend fun getEpisodeSession(
        animeId: Int,
        animeSession: String,
        epNum: Int
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        var page = 1
        var lastPage = 1

        while (page <= lastPage) {
            val url = "$apiUrl/api".toHttpUrl().newBuilder()
                .addQueryParameter("m", "release")
                .addQueryParameter("id", animeId.toString())
                .addQueryParameter("session", animeSession)
                .addQueryParameter("sort", "episode_asc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("l", "30")
                .build()

            val request = Request.Builder()
                .url(url)
                .headers(paheHeaders)
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
                        return@withContext (episode.session ?: return@withContext null) to (episode.audio ?: "jpn")
                    }
                }
            } catch (_: Exception) {
                return@withContext null
            }
            page++
        }
        null
    }

    private suspend fun getLinks(animeId: Int, epSession: String): List<PaheLinkData> = withContext(Dispatchers.IO) {
        val url = "$apiUrl/api".toHttpUrl().newBuilder()
            .addQueryParameter("m", "links")
            .addQueryParameter("id", animeId.toString())
            .addQueryParameter("session", epSession)
            .addQueryParameter("p", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(paheHeaders)
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body.string()
                body.parseAs<PaheLinksResponse>().data ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveKwik(kwikUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(kwikUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "$baseUrl/")
            .get()
            .build()

        try {
            cookieClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body.string()

                val videoUrl = unpackKwik(html)

                if (videoUrl != null) {
                    when {
                        videoUrl.contains(".m3u8") -> {
                            playlistUtils.extractFromHls(videoUrl, videoUrl, paheHeaders, paheHeaders)
                        }
                        else -> {
                            listOf(Video(videoUrl, "$name Kwik", videoUrl, headers = paheHeaders))
                        }
                    }
                } else {
                    val directUrl = Regex(
                        """(https://[^"'\s]+\.mp4[^"'\s]*)"""
                    ).find(html)

                    if (directUrl != null) {
                        listOf(Video(directUrl.value, "$name", directUrl.value))
                    } else {
                        val m3u8Url = Regex(
                            """(https://[^"'\s]+\.m3u8[^"'\s]*)"""
                        ).find(html)

                        if (m3u8Url != null) {
                            playlistUtils.extractFromHls(
                                m3u8Url.value, m3u8Url.value, paheHeaders, paheHeaders
                            )
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun unpackKwik(html: String): String? {
        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = packedRegex.find(html) ?: return null

        val p = match.groupValues[1]
        val a = match.groupValues[2].toInt()
        val c = match.groupValues[3].toInt()
        val k = match.groupValues[4].split("|")

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

        result = result
            .replace("\\/", "/")
            .replace("\\'", "'")
            .replace("\\\"", "\"")

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

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val searchResult = searchAnime(title) ?: return emptyList()
        val animeId = searchResult.id ?: return emptyList()
        val animeSession = searchResult.session ?: return emptyList()

        val (epSession, audio) = getEpisodeSession(animeId, animeSession, meta.epNum)
            ?: return emptyList()

        val links = getLinks(animeId, epSession)
        if (links.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()
        for (link in links) {
            val kwikUrl = link.kwik ?: continue
            val quality = link.quality ?: "unknown"
            val audioLabel = if (audio == "eng" || link.audio == "eng") "Dub" else "Sub"

            val kwikVideos = resolveKwik(kwikUrl)
            for (v in kwikVideos) {
                videos.add(
                    Video(
                        v.url,
                        "$name ${quality}p $audioLabel",
                        v.videoUrl ?: v.url,
                        headers = v.headers
                    )
                )
            }
        }

        return videos
    }
}
