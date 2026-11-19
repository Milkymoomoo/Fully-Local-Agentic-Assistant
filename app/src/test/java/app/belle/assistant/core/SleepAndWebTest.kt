package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepWordsTest {
    private val now = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, ZoneId.of("America/New_York"))
    private val sleep = mutableListOf("goodnight belle", "that's all", "go to sleep", "stop listening")
    private fun brain(phone: FakePhone = FakePhone(), llm: LlmBackend? = null) =
        Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(phone, { true }, { now }), { "Belle" }, { sleep })

    @Test fun exactAndPunctuated() {
        val b = brain()
        assertTrue(b.isSleep("goodnight belle"))
        assertTrue(b.isSleep("Goodnight, Belle!"))
        assertTrue(b.isSleep("That’s all."))          // curly apostrophe from STT
        assertTrue(b.isSleep("go to sleep"))
    }

    @Test fun atEndOfSentence() {
        val b = brain()
        assertTrue(b.isSleep("okay thanks that's all"))
        assertTrue(b.isSleep("alright belle go to sleep"))
        assertTrue(b.isSleep("go to sleep now"))      // short tail after the phrase is fine
    }

    @Test fun notTriggeredInsideRealRequests() {
        val b = brain()
        assertFalse(b.isSleep("remind me to go to sleep at ten tonight please"))
        assertFalse(b.isSleep("is that all the eggs we have"))
        assertFalse(b.isSleep(""))
    }

    @Test fun userAddedMultiWordPhrase() {
        sleep += "we're done here partner"
        assertTrue(brain().isSleep("ok we're done here partner"))
    }

    @Test fun sleepEndsSessionWithoutActionAndClearsPending() {
        val phone = FakePhone()
        val b = brain(phone)
        val first = b.handle("text mom saying on my way")
        assertTrue(b.expectsReply)                       // waiting for yes/no
        val r = b.handle("goodnight belle")
        assertTrue(r.endSession)
        assertFalse(b.expectsReply)                      // pending SMS dropped, not sent
        assertTrue(phone.log.isEmpty())
        assertEquals("sleep", b.lastSource)
        assertTrue(first.speech.isNotEmpty())
    }

    @Test fun historyIsPassedToLlmAndClearedOnSleep() {
        var lastUser = ""
        val llm = object : LlmBackend {
            override val isReady = true
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
                lastUser = user; return "{\"tool\":\"reply\",\"args\":{\"text\":\"Sure.\"}}"
            }
        }
        val b = brain(llm = llm)
        b.handle("tell me a joke about cats")
        b.handle("another one")
        assertTrue(lastUser, lastUser.contains("tell me a joke about cats") && lastUser.endsWith("another one"))
        b.handle("that's all")
        b.handle("one more")
        assertEquals("one more", lastUser)
    }
}

class WebTest {
    @Test fun duckDuckGoHtml() {
        val html = """
            <div class="result results_links results_links_deep web-result">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FMount_Everest&amp;rut=abc">Mount <b>Everest</b> - Wikipedia</a></h2>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Mount Everest is Earth's highest mountain above sea level, 8,849 m.</a>
            </div>
            <div class="result results_links results_links_deep web-result">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://www.britannica.com/place/Mount-Everest">Mount Everest | Height &amp; Facts</a></h2>
              <a class="result__snippet" href="https://www.britannica.com/place/Mount-Everest">The height of Everest&#x27;s summit…</a>
            </div>"""
        val r = SearchParsers.duckDuckGoHtml(html)
        assertEquals(2, r.size)
        assertEquals("https://en.wikipedia.org/wiki/Mount_Everest", r[0].url)
        assertEquals("Mount Everest - Wikipedia", r[0].title)
        assertTrue(r[0].snippet.startsWith("Mount Everest is Earth's"))
        assertEquals("Mount Everest | Height & Facts", r[1].title)
        assertEquals("britannica.com", r[1].domain)
    }

    @Test fun duckDuckGoLite() {
        val html = """<tr><td>1.&nbsp;</td><td><a rel="nofollow" href="https://example.org/a" class='result-link'>Example A</a></td></tr>
            <tr><td>&nbsp;</td><td class='result-snippet'>Snippet <b>A</b> here</td></tr>"""
        val r = SearchParsers.duckDuckGoHtml(html)
        assertEquals("https://example.org/a", r.single().url)
        assertEquals("Snippet A here", r.single().snippet)
    }

    @Test fun braveHtml() {
        val html = """
            <div class="snippet svelte-abc" data-pos="0" data-type="web">
              <a href="https://www.nasa.gov/moon" class="heading-serpresult svelte-abc">
                <div class="site-name-wrapper"><img src="https://imgs.search.brave.com/fav.png"/></div>
                <div class="title search-snippet-title line-clamp-1 svelte-abc" title="Moon - NASA">Moon - NASA</div>
              </a>
              <div class="snippet-content"><div class="snippet-description desktop-default-regular">Earth's Moon is the <strong>only</strong> place beyond Earth where humans have set foot.</div></div>
            </div>
            <div class="snippet svelte-abc" data-pos="1" data-type="web">
              <a href="https://search.brave.com/goggles" ></a><a href="https://en.wikipedia.org/wiki/Moon"><div class="title">Moon - Wikipedia</div></a>
            </div>"""
        val r = SearchParsers.braveHtml(html)
        assertEquals(2, r.size)
        assertEquals("https://www.nasa.gov/moon", r[0].url)
        assertEquals("Moon - NASA", r[0].title)
        assertTrue(r[0].snippet.contains("only place beyond Earth"))
        assertEquals("https://en.wikipedia.org/wiki/Moon", r[1].url)
    }

    @Test fun searxAndWikipedia() {
        val searx = """{"results":[{"title":"T","url":"https://x.org/","content":"c"}]}"""
        assertEquals("https://x.org/", SearchParsers.searx(searx).single().url)
        val wiki = """{"query":{"search":[{"title":"Grace Hopper","snippet":"<span class=\"searchmatch\">Grace</span> Hopper was"}]}}"""
        val w = SearchParsers.wikipedia(wiki).single()
        assertEquals("https://en.wikipedia.org/wiki/Grace_Hopper", w.url)
        assertEquals("Grace Hopper was", w.snippet)
    }

    @Test fun captchaDetected() {
        assertTrue(SearchParsers.looksBlocked("<html><div class='anomaly-modal'>please solve the captcha</div></html>"))
        assertFalse(SearchParsers.looksBlocked("<html>normal results</html>"))
    }

    @Test fun extractReadableText() {
        val html = """<html><head><title>Story</title><script>var x=1;</script><style>p{}</style></head>
            <body><nav><p>Home | About | Contact us today for more</p></nav>
            <article><h1>Big storm hits the coast on Monday evening</h1><p>The storm brought heavy rain and wind gusts of 80 mph to the region, officials said.</p>
            <p>short</p><p>Power was restored to most homes by Tuesday morning, according to the utility company.</p></article>
            <footer><p>Copyright 2026 Some Newspaper Company Incorporated</p></footer></body></html>"""
        val t = HtmlText.extract(html)
        assertTrue(t.contains("heavy rain"))
        assertTrue(t.contains("Power was restored"))
        assertFalse(t.contains("var x"))
        assertFalse(t.contains("Contact us"))
        assertFalse(t.contains("Copyright"))
        assertEquals("Story", HtmlText.title(html))
    }

    @Test fun rssAndAtom() {
        val rss = """<rss><channel><title>BBC</title><item><title><![CDATA[Leaders meet for climate talks]]></title><link>https://bbc.co.uk/1</link>
            <pubDate>Mon, 16 Nov 2026 14:00:00 GMT</pubDate><description>Talks begin.</description></item></channel></rss>"""
        val items = Rss.parse(rss, "BBC")
        assertEquals("Leaders meet for climate talks", items.single().title)
        assertEquals("https://bbc.co.uk/1", items.single().link)
        val atom = """<feed><entry><title>Atom headline</title><link rel="alternate" href="https://g.com/a"/><updated>2026-11-16T10:00:00Z</updated><summary>S</summary></entry></feed>"""
        assertEquals("https://g.com/a", Rss.parse(atom, "G").single().link)
    }

    private class MapFetcher(val pages: Map<String, String>, val enabled: Boolean = true) : Fetcher {
        val requested = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>): String {
            if (!enabled) throw NetDisabledException()
            requested += url
            return pages.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw Exception("HTTP 404")
        }
    }

    @Test fun fallbackOrderAndSummaryWithCitations() {
        val f = MapFetcher(mapOf(
            "https://search.brave.com" to "<html>captcha</html>",
            "https://html.duckduckgo.com" to """<a class="result__a" href="https://a.org/x">A page</a><a class="result__snippet">snip</a>""",
            "https://a.org/x" to "<article><p>The answer is forty two, according to the long running guide book.</p></article>",
        ))
        var prompt = ""
        val llm = object : LlmBackend {
            override val isReady = true
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
                prompt = user; return "**The answer is 42** [1]. See https://a.org/x"
            }
        }
        val w = WebKnowledge(f, { listOf(SearchProvider.BRAVE, SearchProvider.DUCKDUCKGO) }, { "" }, { emptyList() }, { llm })
        val a = w.answer("what is the answer")
        assertEquals(SearchProvider.DUCKDUCKGO, w.lastProvider)
        assertTrue(w.errors.first().contains("captcha"))                         // Brave HTML blocked → fell back
        assertTrue(prompt.contains("[1] A page (a.org)") && prompt.contains("forty two"))
        assertEquals("The answer is 42 [1]. See", a.text)
        assertEquals("The answer is 42. See", WebKnowledge.forSpeech(a.text))
        assertEquals("https://a.org/x", a.sources.single().url)
    }

    @Test fun htmlProvidersUseTheBrowserFetcherAndFrontendOpensTab() {
        val plain = MapFetcher(mapOf("https://en.wikipedia.org/w/api.php" to """{"query":{"search":[]}}"""))
        val browser = MapFetcher(mapOf("https://search.brave.com" to """<div data-type="web"><a href="https://r.org/1"><div class="title">R</div></a></div>""", "https://r.org/1" to "<p>x</p>"))
        val opened = mutableListOf<String>()
        val w = WebKnowledge(plain, { listOf(SearchProvider.BRAVE, SearchProvider.WIKIPEDIA) }, { "" }, { emptyList() }, { null },
            searchFetcher = browser, showInBrowser = { opened += it })
        val a = w.answer("q test")
        assertEquals("https://r.org/1", a.sources.single().url)
        assertTrue(browser.requested.first().startsWith("https://search.brave.com"))
        assertTrue(plain.requested.none { it.contains("brave") })
        assertEquals(listOf("https://search.brave.com/search?source=web&q=q+test"), opened)
    }

    @Test fun frontendScreenReadFallback() {
        val f = MapFetcher(mapOf("https://html.duckduckgo.com" to "<html>captcha</html>", "https://lite.duckduckgo.com" to "<html>captcha</html>"))
        val w = WebKnowledge(f, { listOf(SearchProvider.DUCKDUCKGO) }, { "" }, { emptyList() }, { null },
            screenReader = { "The Eiffel Tower is 330 metres tall, according to the official site of the monument in Paris." })
        val a = w.answer("eiffel tower height")
        assertEquals("Your browser", a.sources.single().title)
        assertTrue(a.text.contains("330 metres"))
    }

    @Test fun newsFiltersByTopic() {
        val f = MapFetcher(mapOf(
            "https://feed1" to "<rss><item><title>Hurricane nears Florida</title><link>https://n/1</link></item><item><title>Stocks rise</title><link>https://n/2</link></item></rss>",
            "https://feed2" to "<rss><item><title>Hurricane season ends</title><link>https://n/3</link></item></rss>",
        ))
        val w = WebKnowledge(f, { emptyList() }, { "" }, { listOf("One|https://feed1", "Two|https://feed2") }, { null })
        val a = w.news("the hurricane")
        assertEquals(2, a.sources.size)
        assertTrue(a.text.startsWith("Here's the latest on the hurricane."))
        assertEquals(3, w.news("").sources.size)
    }

    @Test fun routerRespectsInternetSwitch() {
        val k = object : Knowledge {
            override var enabled = false
            override fun answer(query: String) = WebAnswer("Paris [1].", listOf(Source("P", "https://p.org")))
            override fun news(topic: String) = WebAnswer("", emptyList())
        }
        val r = ActionRouter(FakePhone(), knowledge = k)
        assertTrue(r.execute(Action("web_answer", mapOf("query" to "capital of france"))).speech.contains("internet access is off"))
        k.enabled = true
        val ok = r.execute(Action("web_answer", mapOf("query" to "capital of france")))
        assertEquals("Paris [1].", ok.speech); assertEquals(1, ok.sources.size)
    }

    @Test fun disabledFetcherThrows() {
        val w = WebKnowledge(MapFetcher(emptyMap(), enabled = false), { listOf(SearchProvider.WIKIPEDIA) }, { "" }, { emptyList() }, { null })
        try { w.answer("x"); throw AssertionError("should throw") } catch (_: NetDisabledException) {}
    }
}
