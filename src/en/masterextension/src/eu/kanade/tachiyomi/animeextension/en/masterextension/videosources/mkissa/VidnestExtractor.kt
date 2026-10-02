package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VidnestExtractor(private val client: OkHttpClient, private val headers: Headers) {

    suspend fun videosFromUrl(url: String, embedderUrl: String): List<Video> {
        val embedUrl = url.toHttpUrl()
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val fileCode = embedUrl.pathSegments.lastOrNull { it.isNotEmpty() }
            ?.substringAfterLast("-")
            ?.removeSuffix(".html")
            ?: return emptyList()

        val body = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", fileCode)
            .add("auto", "1")
            .add("referer", embedderUrl)
            .build()

        val postHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()

        val page = client.newCall(POST("$origin/dl", postHeaders, body)).awaitSuccess().bodyString()
        val sources = SOURCES_REGEX.find(page)?.groupValues?.get(1) ?: return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .build()

        return SOURCE_REGEX.findAll(sources).map { match ->
            val file = match.groupValues[1]
            val label = match.groupValues[2]
            Video(
                if (file.startsWith("http")) file else origin + file,
                "Vidnest - ${label.ifEmpty { "Video" }}",
                if (file.startsWith("http")) file else origin + file,
                headers = videoHeaders,
            )
        }.toList()
    }

    companion object {
        private val SOURCES_REGEX = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        private val SOURCE_REGEX = Regex("""file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?""")
    }
}
