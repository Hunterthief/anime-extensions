package eu.kanade.tachiyomi.animeextension.en.masterextension

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gogostreamextractor.GogoStreamExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AllAnimeProvider(
    private val client: OkHttpClient,
    private val headers: Headers
) : VideoProvider {

    override val name = "AllAnime"

    private val apiUrl = "https://api.allanime.day/api"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private val apiHeaders by lazy {
        Headers.Builder().apply {
            add("Accept", "*/*")
            add("Accept-Language", "en-US,en;q=0.9")
            add("Content-Type", "application/json")
            add("Origin", "https://allmanga.to")
            add("Referer", "https://allmanga.to/")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private val xorKeys = arrayOf(
        "allanimenews".toCharArray(),
        "1234567890123456789".toCharArray(),
        "1234567890123456789012345".toCharArray(),
        "s5feqxw21".toCharArray(),
        "feqx1".toCharArray(),
    )

    private fun String.decryptSource(): String {
        val (hexPayload, keyType) = when {
            startsWith("--") -> substring(2) to 3
            startsWith("#-") -> substring(2) to 2
            startsWith("##") -> substring(2) to 1
            startsWith("-#") -> substring(2) to 4
            startsWith("#")  -> substring(1) to 0
            else -> this to null
        }

        if (keyType == null) return this

        val key = xorKeys[keyType]
        val parsedChunks = try {
            hexPayload.chunked(2).map { it.toInt(16) }
        } catch (_: NumberFormatException) {
            return this
        }

        return String(CharArray(parsedChunks.size) { i ->
            ((parsedChunks[i] xor key[i % key.size].code) and 0xFF).toChar()
        })
    }

    private suspend fun fetchShowId(title: String): String = withContext(Dispatchers.IO) {
        val query = """
            query(${'$'}search: SearchInput, ${'$'}limit: Int, ${'$'}page: Int, ${'$'}translationType: VaildTranslationTypeEnumType, ${'$'}countryOrigin: VaildCountryOriginEnumType) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page, translationType: ${'$'}translationType, countryOrigin: ${'$'}countryOrigin) {
                edges { _id name }
              }
            }
        """.trimIndent()

        val payload = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("search", buildJsonObject {
                    put("query", title)
                    put("allowAdult", true)
                    put("allowUnknown", true)
                })
                put("limit", 40)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            })
        }

        val res = makePostRequest(payload) ?: return@withContext ""
        if (res.startsWith("ERR") || res.startsWith("EXC")) return@withContext ""

        try {
            res.parseAs<AllAnimeResponse>().data?.shows?.edges?.firstOrNull()?._id ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun makePostRequest(payload: JsonObject): String? {
        return try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .headers(apiHeaders)
                .build()

            client.newCall(request).execute().use { res ->
                val bodyStr = res.body.string()
                if (!res.isSuccessful) "ERR:${res.code}:${bodyStr.take(30)}"
                else bodyStr
            }
        } catch (e: Exception) {
            "EXC:${e.message?.take(50)}"
        }
    }

    override suspend fun fetchVideos(anime: SAnime, episode: SEpisode): List<Video> {
        val meta = EpisodeMeta.from(episode)
        val title = anime.title.ifBlank { meta.title }
        if (title.isBlank()) return emptyList()

        val showId = fetchShowId(title)
        if (showId.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val variablesJson = buildJsonObject {
                    put("showId", showId)
                    put("translationType", "sub")
                    put("episodeString", meta.epNum.toString())
                }.toString()

                val extensionsJson = buildJsonObject {
                    put("persistedQuery", buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", "4257d8039f3e68b1c41941a7091721483d3d3050")
                    })
                }.toString()

                val url = apiUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("variables", variablesJson)
                    .addQueryParameter("extensions", extensionsJson)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .headers(apiHeaders)
                    .get()
                    .build()

                val responseBody = client.newCall(request).execute().body.string()
                val parsed = responseBody.parseAs<AllAnimeResponse>()
                val sourceUrls = parsed.data?.episode?.sourceUrls ?: emptyList()

                val videos = mutableListOf<Video>()
                for (source in sourceUrls) {
                    val decryptedUrl = source.sourceUrl.decryptSource()

                    when {
                        decryptedUrl.contains(".m3u8") -> {
                            try {
                                videos.addAll(
                                    playlistUtils.extractFromHls(
                                        decryptedUrl, decryptedUrl, apiHeaders, apiHeaders
                                    )
                                )
                            } catch (_: Exception) {
                                videos.add(Video(decryptedUrl, "$name HLS", decryptedUrl, headers = apiHeaders))
                            }
                        }
                        decryptedUrl.contains("filemoon") || decryptedUrl.contains("moon") -> {
                            videos.addAll(filemoonExtractor.videosFromUrl(decryptedUrl, "$name Filemoon"))
                        }
                        decryptedUrl.contains("streamwish") || decryptedUrl.contains("wish") || decryptedUrl.contains("swhoi") -> {
                            videos.addAll(streamwishExtractor.videosFromUrl(decryptedUrl, "$name StreamWish"))
                        }
                        decryptedUrl.contains("mp4upload") -> {
                            videos.addAll(mp4uploadExtractor.videosFromUrl(decryptedUrl, apiHeaders))
                        }
                        decryptedUrl.contains("dood") -> {
                            videos.addAll(doodExtractor.videosFromUrl(decryptedUrl))
                        }
                        decryptedUrl.contains("vidstreaming") || decryptedUrl.contains("gogo") || decryptedUrl.contains("vidcloud") -> {
                            videos.addAll(gogoStreamExtractor.videosFromUrl(decryptedUrl.replace(Regex("^//"), "https://")))
                        }
                        decryptedUrl.contains("streamlare") -> {
                            videos.addAll(streamlareExtractor.videosFromUrl(decryptedUrl))
                        }
                        decryptedUrl.contains("ok.ru") || decryptedUrl.contains("okru") -> {
                            videos.addAll(okruExtractor.videosFromUrl(decryptedUrl))
                        }
                        else -> {
                            if (decryptedUrl.startsWith("http")) {
                                videos.add(Video(decryptedUrl, "$name ${source.sourceName}", decryptedUrl, headers = apiHeaders))
                            }
                        }
                    }
                }
                videos
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
