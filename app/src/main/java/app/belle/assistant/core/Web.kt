package app.belle.assistant.core

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class Source(val title: String, val url: String, val snippet: String = "") {
    val domain: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}
/** [note]: short "switched to DuckDuckGo"-style remark shown (not spoken) when a fallback happened. */
data class WebAnswer(val text: String, val sources: List<Source>, val note: String? = null)
data class NewsItem(val title: String, val link: String, val date: String, val feed: String, val summary: String = "")

/** Thrown whenever code tries to go online while the user's Internet switch is OFF. */
class NetDisabledException : Exception("Internet access is turned off in settings")

/** All network reads go through this. The Android implementation checks the Internet switch first. */
interface Fetcher {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
}

/** No provider needs an account or API key. [html] = a normal results web page (loaded in a real WebView on the phone). */
enum class SearchProvider(val id: String, val label: String, val help: String, val html: Boolean = false) {
    BRAVE("brave", "Brave Search", "Loads search.brave.com like a normal visitor. No account or key.", html = true),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "Loads DuckDuckGo's results page like a normal visitor.", html = true),
    SEARXNG("searxng", "SearXNG (your server)", "Your own or a trusted SearXNG instance with JSON output enabled."),
    WIKIPEDIA("wikipedia", "Wikipedia", "Encyclopedia articles only; very reliable for facts.");

    companion object {
        fun fromId(id: String) = values().firstOrNull { it.id == id }
        val DEFAULT_ORDER = listOf(BRAVE, DUCKDUCKGO, SEARXNG, WIKIPEDIA)
    }
}

/** Parsers for each provider's response. Pure functions, unit-tested with saved pages. */
object SearchParsers {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun url(p: SearchProvider, q: String, searxBase: String = ""): String = when (p) {
        SearchProvider.BRAVE -> "https://search.brave.com/search?source=web&q=${enc(q)}"
        SearchProvider.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=${enc(q)}"
        SearchProvider.SEARXNG -> searxBase.trimEnd('/') + "/search?format=json&q=${enc(q)}"
        SearchProvider.WIKIPEDIA -> "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=${enc(q)}"
    }

    fun parse(p: SearchProvider, body: String): List<Source> = when (p) {
        SearchProvider.BRAVE -> braveHtml(body)
        SearchProvider.DUCKDUCKGO -> duckDuckGoHtml(body)
        SearchProvider.SEARXNG -> searx(body)
        SearchProvider.WIKIPEDIA -> wikipedia(body)
    }

    fun searx(json: String): List<Source> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Source(it.optString("title"), it.optString("url"), it.optString("content"))
        }.filter { it.url.startsWith("http") }
    }

    fun wikipedia(json: String): List<Source> {
        val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val t = it.optString("title")
            Source(t, "https://en.wikipedia.org/wiki/" + t.replace(' ', '_'), HtmlText.strip(it.optString("snippet")))
        }
    }

    /** html.duckduckgo.com and lite.duckduckgo.com result pages. */
    fun duckDuckGoHtml(html: String): List<Source> {
        val out = mutableListOf<Source>()
        val linkRe = Regex("""<a[^>]+class=["'][^"']*(?:result__a|result-link)[^"']*["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val hrefRe = Regex("""href=["']([^"']+)["']""")
        val snipRe = Regex("""class=["'][^"']*result(?:__|-)snippet[^"']*["'][^>]*>(.*?)</(?:a|td|div)>""", RegexOption.DOT_MATCHES_ALL)
        val snippets = snipRe.findAll(html).map { HtmlText.strip(it.groupValues[1]) }.toList()
        linkRe.findAll(html).forEachIndexed { i, m ->
            var href = hrefRe.find(m.value)?.groupValues?.get(1) ?: return@forEachIndexed
            href = HtmlText.decodeEntities(href)
            if (href.contains("uddg=")) href = URLDecoder.decode(href.substringAfter("uddg=").substringBefore('&'), "UTF-8")
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) return@forEachIndexed
            out += Source(HtmlText.strip(m.groupValues[1]), href, snippets.getOrElse(i) { "" })
        }
        return out.distinctBy { it.url }
    }

    /** search.brave.com results page (keyless). Tolerant of class-name changes. */
    fun braveHtml(html: String): List<Source> {
        val out = mutableListOf<Source>()
        val blocks = html.split(Regex("""data-type=["']web["']""")).drop(1)
        for (b in blocks) {
            val chunk = b.take(6000)
            val href = Regex("""href=["'](https?://[^"']+)["']""").findAll(chunk).map { HtmlText.decodeEntities(it.groupValues[1]) }
                .firstOrNull { !it.contains("brave.com") && !it.contains("imgs.search") } ?: continue
            val title = Regex("""class=["'][^"']*(?:snippet-title|search-snippet-title|title)[^"']*["'][^>]*>(.*?)</(?:div|span|a)>""", RegexOption.DOT_MATCHES_ALL)
                .find(chunk)?.groupValues?.get(1)?.let { HtmlText.strip(it) }.orEmpty()
            val desc = Regex("""class=["'][^"']*(?:snippet-description|generic-snippet|content)[^"']*["'][^>]*>(.*?)</(?:div|p)>""", RegexOption.DOT_MATCHES_ALL)
                .find(chunk)?.groupValues?.get(1)?.let { HtmlText.strip(it) }.orEmpty()
            if (title.isNotEmpty()) out += Source(title, href, desc)
        }
        return out.distinctBy { it.url }
    }

    fun looksBlocked(body: String) = body.length < 200000 && Regex("(?i)captcha|anomaly-modal|are you a robot|unusual traffic").containsMatchIn(body)
}

/** Readable-text extraction from web pages (scripts, styles, nav, footers removed). */
object HtmlText {
    private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&#x27;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”")

    fun decodeEntities(s: String): String {
        var t = s
        for ((k, v) in entities) t = t.replace(k, v)
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return t
    }

    fun strip(html: String): String = decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun extract(html: String, maxChars: Int = 2500): String {
        var h = html
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "footer", "header", "aside", "form", "template")) {
            h = h.replace(Regex("<$tag\\b.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        }
        h = h.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val article = Regex("<(article|main)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(h)?.value ?: h
        // Paragraphs first (real prose); fall back to headings and list items for thin pages.
        var paras = Regex("<p\\b[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(article).map { strip(it.groupValues[1]) }.filter { it.length >= 60 }.toList()
        if (paras.sumOf { it.length } < 300) paras = Regex("<(p|h1|h2|h3|li)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(article).map { strip(it.groupValues[2]) }.filter { it.length >= 40 }.toList()
        paras = paras.map { it.replace(Regex("\\[\\d+]"), "") }   // drop Wikipedia-style footnote marks
        val text = (if (paras.isNotEmpty()) paras.joinToString("\n") else strip(article))
        return text.take(maxChars)
    }

    fun title(html: String): String = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
}

/** Tolerant RSS 2.0 / Atom parser (no Android dependencies, unit-tested). */
object Rss {
    fun parse(xml: String, feedName: String): List<NewsItem> {
        val items = Regex("<(item|entry)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml)
        return items.mapNotNull { m ->
            val b = m.value
            fun tag(name: String) = Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(b)?.groupValues?.get(1)?.let { cdata(it) }?.let { HtmlText.strip(it) }.orEmpty()
            val title = tag("title")
            if (title.isEmpty()) return@mapNotNull null
            val link = tag("link").ifEmpty {
                Regex("<link[^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1).orEmpty()
            }
            NewsItem(title, link, tag("pubDate").ifEmpty { tag("updated").ifEmpty { tag("dc:date") } }, feedName, tag("description").ifEmpty { tag("summary") }.take(300))
        }.toList()
    }

    private fun cdata(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")

    val DEFAULT_FEEDS = listOf(
        "BBC World|https://feeds.bbci.co.uk/news/world/rss.xml",
        "NPR|https://feeds.npr.org/1001/rss.xml",
        "PBS NewsHour|https://www.pbs.org/newshour/feeds/rss/headlines",
        "The Guardian|https://www.theguardian.com/world/rss",
        "Al Jazeera|https://www.aljazeera.com/xml/rss/all.xml",
    )
}

/**
 * Web knowledge pipeline: search (provider fallback order) → fetch top pages →
 * extract readable text → local LLM writes a short spoken answer with [n]
 * citations → sources returned for display. Also "latest news" from RSS.
 */
class WebKnowledge(
    /** Plain HTTPS reads: JSON APIs (Wikipedia, SearXNG), RSS, and result pages. */
    private val fetcher: Fetcher,
    private val providers: () -> List<SearchProvider>,
    private val searxUrl: () -> String,
    private val feeds: () -> List<String>,
    private val llm: () -> LlmBackend?,
    private val pagesToRead: () -> Int = { 3 },
    /** Loads HTML search pages. On the phone: a hidden WebView ("headless" browsing). */
    private val searchFetcher: Fetcher = fetcher,
    /** "Frontend" browsing: also show the search in the user's browser. Called with the first provider's URL. */
    private val showInBrowser: ((String) -> Unit)? = null,
    /** Optional: text read off the screen (accessibility) after the browser tab loads. */
    private val screenReader: (() -> String?)? = null,
    val limiter: RateLimiter = RateLimiter(),
    /** Parallel page reads (spread across different sites). */
    private val pagePool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3),
) {
    /** Set when the first-choice provider was skipped or failed and another one answered. */
    var fallbackNote: String? = null
        private set
    var lastProvider: SearchProvider? = null
        private set
    val errors = mutableListOf<String>()

    fun search(query: String): List<Source> = limiter.dedupe("search|$query") { searchUncached(query) }

    private fun searchUncached(query: String): List<Source> {
        errors.clear(); fallbackNote = null
        val order = providers().filter { it != SearchProvider.SEARXNG || searxUrl().isNotBlank() }
        val first = order.firstOrNull()
        for (p in order) {
            limiter.cached<List<Source>>("${p.id}|$query")?.let { lastProvider = p; return it }
        }
        for (p in order) {
            if (p == SearchProvider.SEARXNG && searxUrl().isBlank()) continue
            if (!limiter.tryAcquire(p.id)) { errors += "${p.label}: busy, skipped"; continue }   // never wait: fail over
            try {
                val headers = mutableMapOf<String, String>()
                if (!p.html) headers["Accept"] = "application/json"
                val urls = listOf(SearchParsers.url(p, query, searxUrl())) +
                    if (p == SearchProvider.DUCKDUCKGO) listOf("https://lite.duckduckgo.com/lite/?q=${SearchParsers.enc(query)}") else emptyList()
                for (u in urls) {
                    val body = (if (p.html) searchFetcher else fetcher).get(u, headers)
                    val res = SearchParsers.parse(p, body)
                    if (res.isNotEmpty()) {
                        lastProvider = p; limiter.success(p.id); limiter.put("${p.id}|$query", res, RateLimiter.SEARCH_TTL)
                        if (p != first && first != null) fallbackNote = "${first.label} was busy — switched to ${p.label}."
                        return res
                    }
                    val blocked = SearchParsers.looksBlocked(body)
                    errors += "${p.label}: " + if (blocked) "asked for a captcha" else "no results"
                    if (blocked) { limiter.penalize(p.id); break }
                }
            } catch (e: NetDisabledException) { throw e } catch (e: Exception) {
                errors += "${p.label}: ${e.message}"
                if (RateLimiter.isRateLimit(e.message)) limiter.penalize(p.id, RateLimiter.retryAfterMs(e.message))
            }
        }
        return emptyList()
    }

    fun answer(question: String): WebAnswer {
        val usable = providers().filter { it != SearchProvider.SEARXNG || searxUrl().isNotBlank() }
        usable.firstOrNull()?.let { p -> showInBrowser?.invoke(SearchParsers.url(p, question, searxUrl())) }
        var results = search(question)
        if (results.isEmpty() && screenReader != null) {
            // Frontend mode fallback: whatever the browser tab shows (read via accessibility).
            screenReader.invoke()?.takeIf { it.length > 80 }?.let { results = listOf(Source("Your browser", "about:screen", it.take(1500))) }
        }
        if (results.isEmpty()) return WebAnswer(
            if (errors.isNotEmpty() && errors.all { "no results" !in it }) "I couldn't reach any of your search services just now. Try again in a bit."
            else "I couldn't find anything online for that.", emptyList())
        val top = results.take(pagesToRead())
        // Read up to 3 pages at once; each from a different site.
        val futures = top.map { s ->
            pagePool.submit<String> {
                if (!s.url.startsWith("http")) s.snippet else try {
                    limiter.cached<String>("page|${s.url}") ?: pageText(s.url).also { limiter.put("page|${s.url}", it, RateLimiter.SEARCH_TTL) }
                } catch (e: NetDisabledException) { throw e } catch (e: Exception) { "" }
            }
        }
        val pages = top.mapIndexed { i, s ->
            val text = try { futures[i].get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) {
                if (e.cause is NetDisabledException) throw e.cause!!; ""
            } catch (e: Exception) { "" }
            "[${i + 1}] ${s.title} (${s.domain})\n" + text.ifBlank { s.snippet }
        }
        val l = llm()
        val text = if (l != null && l.isReady) {
            val out = l.complete(SUMMARY_SYSTEM, "Question: $question\n\nSources:\n" + pages.joinToString("\n\n"), "", 180).trim()
            cleanAnswer(out).ifBlank { fallback(top) }
        } else fallback(top)
        return WebAnswer(text, top, fallbackNote)
    }

    /** Readable text of a result page. Wikipedia articles use the clean summary API instead of HTML. */
    fun pageText(url: String): String {
        val wiki = Regex("^https://([a-z]{2,3})\\.(?:m\\.)?wikipedia\\.org/wiki/([^#?]+)").find(url)
        if (wiki != null) runCatching {
            val (lang, title) = wiki.destructured
            val j = JSONObject(fetcher.get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$title", mapOf("Accept" to "application/json")))
            j.optString("extract").takeIf { it.length > 80 }
        }.getOrNull()?.let { return it.take(1800) }
        return HtmlText.extract(fetcher.get(url), 1800)
    }

    private fun fallback(top: List<Source>): String {
        val s = top.firstOrNull { it.snippet.isNotBlank() } ?: return "Here's what I found: ${top.first().title} [1]."
        return "According to ${s.domain}: ${s.snippet.take(260)} [${top.indexOf(s) + 1}]"
    }

    fun news(topic: String): WebAnswer {
        val all = mutableListOf<NewsItem>()
        for (line in feeds()) {
            val name = line.substringBefore('|').trim()
            val url = line.substringAfter('|').trim()
            if (!url.startsWith("http")) continue
            try {
                all += limiter.cached<List<NewsItem>>("rss|$url") ?: Rss.parse(fetcher.get(url), name).take(15).also { limiter.put("rss|$url", it, RateLimiter.NEWS_TTL) }
            } catch (e: NetDisabledException) { throw e } catch (_: Exception) {}
        }
        if (all.isEmpty()) return WebAnswer("I couldn't load any news feeds right now.", emptyList())
        val words = topic.lowercase().split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("the", "news", "latest", "about", "today") }
        val picked = if (words.isEmpty()) roundRobin(all) else all.filter { n -> words.any { n.title.lowercase().contains(it) || n.summary.lowercase().contains(it) } }
        if (picked.isEmpty()) return WebAnswer("I didn't see any headlines about $topic in your news feeds.", emptyList())
        val top = picked.take(5)
        val spoken = (if (words.isEmpty()) "Here are the latest headlines. " else "Here's the latest on $topic. ") +
            top.mapIndexed { i, n -> "${n.title} [${i + 1}]." }.joinToString(" ")
        return WebAnswer(spoken, top.map { Source(it.title, it.link, it.feed) })
    }

    private fun roundRobin(items: List<NewsItem>): List<NewsItem> {
        val byFeed = items.groupBy { it.feed }.values.map { it.toMutableList() }
        val out = mutableListOf<NewsItem>()
        while (out.size < 10 && byFeed.any { it.isNotEmpty() }) for (l in byFeed) if (l.isNotEmpty()) out += l.removeAt(0)
        return out
    }

    companion object {
        const val SUMMARY_SYSTEM = "You answer the user's question using ONLY the numbered web sources given. Reply in 1 to 3 short, plain sentences that will be read aloud. " +
            "After each fact, put the number of the source it came from in square brackets, for example: It is about 8,849 meters tall [1]. " +
            "Only mention disagreement if sources clearly contradict each other; small rounding or unit differences are not disagreements. " +
            "If the sources don't answer the question, say you couldn't confirm it. No lists, no markdown, no links."

        fun cleanAnswer(s: String) = s.replace(Regex("[*#`_]"), "").replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim()

        /** Remove [n] markers for speech. */
        fun forSpeech(s: String) = s.replace(Regex("\\s*\\[\\d+(?:,\\s*\\d+)*]"), "").trim()
    }
}
