package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RateLimiterTest {
    private var t = 0L
    private fun limiter() = RateLimiter(now = { t }, random = Random(1))

    @Test fun burstThenRefill() {
        val l = limiter()
        assertTrue(l.tryAcquire("brave")); assertTrue(l.tryAcquire("brave"))
        assertFalse(l.tryAcquire("brave"))                 // burst of 2 used up
        assertTrue(l.tryAcquire("duckduckgo"))             // other providers unaffected
        t += 2500; assertTrue(l.tryAcquire("brave"))       // one token per 2.5 s
        assertFalse(l.tryAcquire("brave"))
    }

    @Test fun backoffHonoursRetryAfterAndGrows() {
        val l = limiter()
        l.penalize("brave", retryAfterMs = 120_000)
        assertTrue(l.isBlocked("brave"))
        t += 100_000; assertTrue(l.isBlocked("brave"))
        t += 25_000; assertFalse(l.isBlocked("brave"))
        l.success("brave")
        l.penalize("brave"); val first = l.waitMs("brave")
        t += first; l.penalize("brave"); val second = l.waitMs("brave")
        assertTrue("exponential: $first then $second", second > first)
        assertTrue(first in 22_500L..37_500L)              // 30 s ± 25 % jitter
        l.success("brave"); t += second; l.penalize("brave")
        assertTrue(l.waitMs("brave") <= 37_500L)           // strikes reset after success
    }

    @Test fun cacheExpires() {
        val l = limiter()
        l.put("k", listOf(1), 1000)
        assertEquals(listOf(1), l.cached<List<Int>>("k"))
        t += 1001; assertNull(l.cached<List<Int>>("k"))
        assertEquals(10 * 60_000L, RateLimiter.SEARCH_TTL); assertEquals(30 * 60_000L, RateLimiter.NEWS_TTL)
    }

    @Test fun retryAfterParsing() {
        assertEquals(12_000L, RateLimiter.retryAfterMs("HTTP 429 retry-after=12"))
        assertTrue(RateLimiter.isRateLimit("HTTP 429")); assertFalse(RateLimiter.isRateLimit("HTTP 500"))
    }

    @Test fun dedupeSharesOneCall() {
        val l = limiter(); var calls = 0
        val gate = java.util.concurrent.CountDownLatch(1)
        val th = Thread { val r: String = l.dedupe("q") { gate.await(); calls++; "r" }; check(r == "r") }.apply { start() }
        Thread.sleep(100)
        var second: String? = null
        val th2 = Thread { second = l.dedupe("q") { calls++; "other" } }.apply { start() }
        Thread.sleep(100); gate.countDown(); th.join(); th2.join()
        assertEquals(1, calls); assertEquals("r", second)
    }

    private class Counting(val pages: Map<String, String>, val fail: Map<String, String> = emptyMap()) : Fetcher {
        val hits = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>): String {
            hits += url
            fail.entries.firstOrNull { url.startsWith(it.key) }?.let { throw Exception(it.value) }
            return pages.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw Exception("HTTP 404")
        }
    }

    @Test fun failoverOn429IsInstantAndProviderBacksOff() {
        val f = Counting(
            mapOf("https://html.duckduckgo.com" to """<a class="result__a" href="https://a.org/x">A</a>""", "https://a.org/x" to "<p>x</p>"),
            fail = mapOf("https://search.brave.com" to "HTTP 429 retry-after=60"))
        val lim = limiter()
        val w = WebKnowledge(f, { listOf(SearchProvider.BRAVE, SearchProvider.DUCKDUCKGO) }, { "" }, { emptyList() }, { null }, limiter = lim)
        val a = w.answer("first question")
        assertEquals(SearchProvider.DUCKDUCKGO, w.lastProvider)
        assertEquals("Brave Search was busy — switched to DuckDuckGo.", a.note)
        assertTrue(lim.isBlocked("brave"))
        f.hits.clear(); t += 5000
        w.answer("second question")
        assertTrue("brave skipped while backing off: ${f.hits}", f.hits.none { it.contains("brave") })
    }

    @Test fun repeatQuestionsComeFromCache() {
        val f = Counting(mapOf("https://search.brave.com" to """<div data-type="web"><a href="https://r.org/1"><div class="title">R</div></a></div>""", "https://r.org/1" to "<p>x</p>"))
        val w = WebKnowledge(f, { listOf(SearchProvider.BRAVE) }, { "" }, { emptyList() }, { null }, limiter = limiter())
        w.answer("same"); val n = f.hits.size
        w.answer("same"); w.answer("same")
        assertEquals(n, f.hits.size)                        // no new network requests
    }

    @Test fun busyProviderIsSkippedNotAwaited() {
        val f = Counting(mapOf(
            "https://search.brave.com" to """<div data-type="web"><a href="https://r.org/1"><div class="title">R</div></a></div>""",
            "https://html.duckduckgo.com" to """<a class="result__a" href="https://d.org/1">D</a>""", "https://r.org/1" to "<p/>", "https://d.org/1" to "<p/>"))
        val w = WebKnowledge(f, { listOf(SearchProvider.BRAVE, SearchProvider.DUCKDUCKGO) }, { "" }, { emptyList() }, { null }, limiter = limiter())
        w.answer("q1"); w.answer("q2")
        val t0 = System.nanoTime(); w.answer("q3")          // brave's burst is spent → DDG, no sleeping
        assertEquals(SearchProvider.DUCKDUCKGO, w.lastProvider)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
    }
}
