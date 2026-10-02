package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class CloudflareInterceptor(
    private val client: OkHttpClient,
    private val cfBypassUserAgentProvider: () -> String = { ANIMEPAHE_UA },
) : Interceptor {

    private val bypassLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        if (response.code !in ERROR_CODES) {
            return response
        }

        val isCloudflare = response.header("cf-ray") != null
        response.close()

        if (isCloudflare) {
            val customUA = cfBypassUserAgentProvider()

            val bypassResult = synchronized(bypassLock) {
                CloudflareBypass().getCookies(
                    pageUrl = originalRequest.url.toString(),
                    customUserAgent = customUA,
                )
            }

            if (bypassResult != null) {
                return chain.proceed(
                    originalRequest.newBuilder()
                        .header("Cookie", bypassResult.cookies)
                        .header("User-Agent", bypassResult.userAgent)
                        .build(),
                )
            }
        }

        return response
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
    }
}
