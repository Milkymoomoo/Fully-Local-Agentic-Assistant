package app.belle.assistant.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTextTest {
    private val P = BookText.PARA

    @Test fun pageNumbersAndRunningHeadersGo() {
        val pages = (1..12).map { "${P}THE LONG ROAD\n${P}Line of story number $it goes on.\n${P}$it/12" }
        val c = BookText.stripRunning(pages)
        assertTrue(c.all { !it.contains("/12") && !it.contains("THE LONG ROAD") && it.contains("story number") })
    }

    @Test fun hyphensAndWrappedLinesRejoin() {
        val dict = BookText.dictionary(listOf("her blue-black hair\nwas long"))
        val p = BookText.paragraphs("${P}The warm hues of the af-\nternoon faded and her blue-\nblack hair\nshone.\n$P• • •\n${P}Next one.", dict, false)
        assertEquals(listOf("The warm hues of the afternoon faded and her blue-black hair shone.", "", "Next one."), p)
    }

    @Test fun paragraphsContinueAcrossPages() {
        val j = BookText.joinAcrossPages(listOf(listOf("First para ends here.", "This one runs on to the"), listOf("next page and ends.", "New para.")))
        assertEquals(listOf("First para ends here.", "This one runs on to the next page and ends.", "New para."), j)
    }

    @Test fun boilerplateAndFrontMatterAreNotRead() {
        val story = "word ".repeat(600)
        val d = BookText.assemble("T", "", "pdf", listOf(
            "Front matter" to listOf("Thank you for purchasing this eBook.", "CLICK HERE TO SIGN UP"),
            "CONTENTS" to listOf("Chapter 1", "Chapter 2"),
            "1" to listOf("1", story), "2" to listOf(story),
            "EPILOGUE" to listOf("Epilogue", story), "ACKNOWLEDGMENTS" to listOf("Thanks to all."), "THE BATTLE RAGES ON" to listOf(story)))
        assertEquals(listOf("Chapter 1", "Chapter 2", "Epilogue"), d.readable.map { it.title })
        assertEquals(Kind.FRONT, d.chapters[0].kind); assertEquals(Kind.BACK, d.chapters.last().kind)
        assertFalse(d.readable[0].text.startsWith("1"))
    }

    @Test fun sentencesKeepAbbreviationsAndQuotes() {
        val s = BookText.sentences("“Come here,” said Mr. Smith. She did. Then they left the old house together!\n\nA new paragraph starts. It ends.")
        assertEquals(listOf("“Come here,” said Mr. Smith.", "She did. Then they left the old house together!", "", "A new paragraph starts. It ends."), s)
        assertTrue(BookText.sentences("x ".repeat(400)).all { it.length <= 281 })
    }

    @Test fun txtChaptersAndGutenbergWrapper() {
        val body = "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nTitle\n\nCHAPTER I.\n\nIt was a dark\nand stormy night.\n\nCHAPTER II.\n\nMorning came.\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\nlicence text"
        val d = BookText.fromTxt("x", body)
        assertEquals(listOf("Chapter I.", "Chapter II."), d.readable.map { it.title })
        assertEquals("It was a dark and stormy night.", d.readable[0].text)
        assertFalse(d.chapters.any { it.text.contains("licence") })
    }

    private fun epub(): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><dc:title>My &amp; Book</dc:title><dc:creator>A. Writer</dc:creator></metadata><manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="a2" href="text/ch1b.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="text/ch2.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c"/><itemref idref="a"/><itemref idref="a2"/><itemref idref="b"/></spine></package>""")
            put("OEBPS/nav.xhtml", """<html><body><nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">One</a></li><li><a href="text/ch2.xhtml#s">Two</a></li></ol></nav></body></html>""")
            put("OEBPS/text/cover.xhtml", """<html><body epub:type="cover"><p>Cover</p></body></html>""")
            put("OEBPS/text/ch1.xhtml", """<html><head><title>x</title><style>p{}</style></head><body><h1>One</h1><p>It &ldquo;began&rdquo; here<sup><a href="#n1">1</a></sup>.</p><hr/><p>Then&nbsp;more.</p></body></html>""")
            put("OEBPS/text/ch1b.xhtml", """<html><body><p>Still chapter one.</p></body></html>""")
            put("OEBPS/text/ch2.xhtml", """<html><body><h2>Two</h2><p>The end.</p></body></html>""")
        }
        return bo.toByteArray()
    }

    @Test fun epubSpineTocAndMergedFiles() {
        val d = Epub.parse(epub())
        assertEquals("My & Book", d.title); assertEquals("A. Writer", d.author)
        assertEquals(listOf("One", "Two"), d.readable.map { it.title })
        assertEquals("It “began” here.\n\nThen more.\n\nStill chapter one.", d.readable[0].text)
        assertEquals(2, d.chapters.size)   // the cover page has nothing to read
    }
}
