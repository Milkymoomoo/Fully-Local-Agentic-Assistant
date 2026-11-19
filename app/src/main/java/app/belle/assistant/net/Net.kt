package app.belle.assistant.net

import app.belle.assistant.Prefs
import app.belle.assistant.core.Fetcher
import app.belle.assistant.core.NetDisabledException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The ONLY place Belle opens network connections. Every call checks the
 * "Internet access" switch first (OFF by default) and refuses otherwise.
 * HTTPS only, no cookies kept, no identifiers sent, 1.5 MB cap per page.
 */
class Net(private val prefs: Prefs) : Fetcher {
    override fun get(url: String, headers: Map<String, String>): String {
        if (!prefs.internetEnabled) throw NetDisabledException()
        var u = URL(url)
        repeat(5) {
            if (u.protocol != "https" && !(u.protocol == "http" && isLocal(u.host))) throw IllegalArgumentException("Only https is allowed")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> if (k != "Accept") c.setRequestProperty(k, v) }
            c.useCaches = false
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: throw Exception("bad redirect")); return@repeat }
                if (code !in 200..299) throw Exception("HTTP $code" + (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { " retry-after=$it" } ?: ""))
                val raw = if (c.contentEncoding == "gzip") GZIPInputStream(c.inputStream) else c.inputStream
                raw.use { ins ->
                    val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
                    while (out.size() < MAX) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    return out.toString(charset(c.contentType))
                }
            } finally { c.disconnect() }
        }
        throw Exception("too many redirects")
    }

    private fun charset(ct: String?): String =
        ct?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.ifEmpty { null }?.takeIf { runCatching { java.nio.charset.Charset.forName(it) }.isSuccess } ?: "UTF-8"

    /** Lets a user's SearXNG on their home network use plain http. */
    private fun isLocal(h: String) = h == "localhost" || h.startsWith("192.168.") || h.startsWith("10.") || h.endsWith(".local") || h.endsWith(".lan") ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)

    companion object {
        const val MAX = 1_500_000
        /** A common desktop browser UA so results pages render normally; nothing device-specific. */
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
