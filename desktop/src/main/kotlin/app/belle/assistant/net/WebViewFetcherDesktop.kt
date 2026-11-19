package app.belle.assistant.net

import android.content.Context
import app.belle.assistant.Prefs
import app.belle.assistant.core.Fetcher

/**
 * Desktop twin of the phone's hidden-WebView page fetcher (used for search result pages). The desktop has no embedded
 * browser, so it fetches through [Net] — still the only place connections are opened, still refused while
 * *Internet access* is off. Pages that need JavaScript return less text than on the phone.
 */
class WebViewFetcher(private val ctx: Context, private val prefs: Prefs) : Fetcher {
    private val net = Net(prefs)
    override fun get(url: String, headers: Map<String, String>): String = net.get(url, headers)
    /** Nothing is cached on the desktop (Net keeps no cookies or cache). */
    fun clear() {}
}
