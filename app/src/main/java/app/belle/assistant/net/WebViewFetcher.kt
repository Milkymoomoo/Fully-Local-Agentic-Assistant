package app.belle.assistant.net

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.belle.assistant.Prefs
import app.belle.assistant.core.Fetcher
import app.belle.assistant.core.NetDisabledException
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Headless" browsing: a hidden, never-shown WebView loads the real search
 * results page exactly like the phone's browser would — real mobile Chrome
 * engine and User-Agent, JavaScript, cookies for that site, from your own
 * home/mobile IP — then hands back the rendered HTML for parsing.
 *
 * This is the main defence against "429 Too Many Requests" and captchas that
 * plain HTTP clients (and server IPs) get. If a page still shows a captcha the
 * caller just moves on to the next provider.
 *
 * Privacy: only runs when Internet access is ON; images, fonts and media are
 * not loaded; third-party requests (ads/trackers) are blocked; no Google
 * services are involved (Android System WebView is just the rendering engine).
 */
class WebViewFetcher(private val ctx: Context, private val prefs: Prefs) : Fetcher {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var web: WebView? = null

    override fun get(url: String, headers: Map<String, String>): String {
        if (!prefs.internetEnabled) throw NetDisabledException()
        check(Looper.myLooper() != Looper.getMainLooper()) { "call from a background thread" }
        val done = CountDownLatch(1)
        var html: String? = null
        var error: String? = null
        val host = android.net.Uri.parse(url).host.orEmpty().removePrefix("www.").let { h -> h.split('.').takeLast(2).joinToString(".") }
        main.post {
            val w = web ?: create().also { web = it }
            var finished = false
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val h = req.url.host.orEmpty()
                    val sameSite = h == host || h.endsWith(".$host")
                    val path = req.url.path.orEmpty().lowercase()
                    val heavy = Regex("\\.(png|jpe?g|gif|webp|svg|woff2?|ttf|mp4|webm|ico)$").containsMatchIn(path)
                    return if (!sameSite || heavy) WebResourceResponse("text/plain", "utf-8", null) else null
                }
                override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                    if (req.isForMainFrame) { error = "HTTP ${resp.statusCode}"; finish(view) }
                }
                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { finished = false }
                override fun onPageFinished(view: WebView, u: String?) {
                    if (finished) return
                    finished = true
                    // Give client-side rendering a moment (Brave renders results with JS).
                    main.postDelayed({ grab(view) }, 1200)
                }
                fun grab(view: WebView) {
                    view.evaluateJavascript("document.documentElement.outerHTML") { js ->
                        html = runCatching { JSONArray("[$js]").getString(0) }.getOrNull()
                        done.countDown()
                    }
                }
                fun finish(view: WebView) { view.stopLoading(); done.countDown() }
            }
            w.loadUrl(url)
        }
        if (!done.await(20, TimeUnit.SECONDS)) { main.post { web?.stopLoading() }; throw Exception("timed out") }
        error?.let { throw Exception(it) }
        return html ?: throw Exception("empty page")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(ctx.applicationContext).apply {
        settings.javaScriptEnabled = true          // results pages need it
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.safeBrowsingEnabled = false       // Safe Browsing would send URLs to Google
        // The phone's real Chrome UA, minus the "; wv" WebView marker, so sites treat it as the normal mobile browser.
        settings.userAgentString = WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        layout(0, 0, 1080, 2200)                   // never attached to a window
    }

    /** Settings › Internet › "Clear browsing data". */
    fun clear() = main.post {
        web?.clearCache(true); web?.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    fun release() = main.post { web?.destroy(); web = null }
}
