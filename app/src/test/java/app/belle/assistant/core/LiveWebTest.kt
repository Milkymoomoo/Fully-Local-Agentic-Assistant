package app.belle.assistant.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in: BELLE_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveWebTest*' — hits the real providers. */
class LiveWebTest {
    private val fetch = object : Fetcher {
        override fun get(url: String, headers: Map<String, String>): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 8000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun providers() {
        assumeTrue(System.getenv("BELLE_LIVE") == "1")
        for (p in listOf(SearchProvider.WIKIPEDIA, SearchProvider.DUCKDUCKGO, SearchProvider.BRAVE)) {
            val r = runCatching { val b = fetch.get(SearchParsers.url(p, "mount everest height")); SearchParsers.parse(p, b) to SearchParsers.looksBlocked(b) }
            println("LIVE ${p.id}: " + r.fold({ "${it.first.size} results blocked=${it.second} first=${it.first.firstOrNull()}" }, { "ERROR ${it.message}" }))
        }
        for (line in Rss.DEFAULT_FEEDS) {
            val url = line.substringAfter('|')
            println("LIVE feed ${line.substringBefore('|')}: " + runCatching { Rss.parse(fetch.get(url), "x").let { "${it.size} items, first=${it.firstOrNull()?.title}" } }.getOrElse { "ERROR ${it.message}" })
        }
        val w = WebKnowledge(fetch, { emptyList() }, { "" }, { emptyList() }, { null })
        println("LIVE wiki summary: " + w.pageText("https://en.wikipedia.org/wiki/Mount_Everest").take(200))
        println("LIVE extract npr: " + HtmlText.extract(fetch.get("https://www.npr.org/"), 300))
        val ddg = WebKnowledge(fetch, { listOf(SearchProvider.BRAVE, SearchProvider.DUCKDUCKGO) }, { "" }, { emptyList() }, { null })
        val a = ddg.answer("who wrote pride and prejudice")
        println("LIVE answer(no llm): ${a.text} via ${ddg.lastProvider} errors=${ddg.errors}")
    }
}
