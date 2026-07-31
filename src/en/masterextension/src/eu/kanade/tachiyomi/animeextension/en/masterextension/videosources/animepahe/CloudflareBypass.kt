package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.animepahe

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val ANIMEPAHE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

data class CloudFlareBypassResult(
    val cookies: String,
    val userAgent: String,
)

class CloudflareBypass {

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun getCookies(pageUrl: String, customUserAgent: String? = null): CloudFlareBypassResult? {
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        val userAgentToUse = customUserAgent ?: ANIMEPAHE_UA

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = userAgentToUse

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, userAgentToUse, cancelled) { bypassResult ->
                        result = bypassResult
                        latch.countDown()
                    }
                }
            }

            CookieManager.getInstance().setCookie(pageUrl, "")
            webView.loadUrl(pageUrl)
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post {
                webView?.destroy()
            }
        }

        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        cancelled: AtomicBoolean,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val maxDurationMs = 30_000L
        val pollIntervalMs = 500L

        val runnable = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= maxDurationMs) return

                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    onComplete(CloudFlareBypassResult(cookies, userAgent))
                } else {
                    handler.postDelayed(this, pollIntervalMs)
                }
            }
        }
        handler.post(runnable)
    }

    private fun clearCookiesForUrl(pageUrl: String) {
        val domain = Uri.parse(pageUrl).host ?: return
        val cookieManager = CookieManager.getInstance()

        listOf("https://$domain", "https://www.$domain").forEach { url ->
            cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                val cookieName = cookieStr.substringBefore("=").trim()
                if (cookieName.isNotEmpty()) {
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/; domain=.$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
