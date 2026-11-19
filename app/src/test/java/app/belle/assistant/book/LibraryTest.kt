package app.belle.assistant.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibraryTest {
    @Test fun addSaveResumeDelete() {
        val lib = Library(Files.createTempDirectory("lib").toFile())
        val e = lib.add(BookDoc("Tides of Test", "Me", "txt", listOf(Chapter("Contents", Kind.FRONT, "1 2"), Chapter("Chapter 1", Kind.CHAPTER, "One. Two."))), "t.txt")
        assertEquals(listOf(1), e.readable)
        assertEquals("One. Two.", lib.text(e.id, 1))
        lib.save(e.id, Library.Position(1, 5, 123L))
        assertEquals(Library.Position(1, 5, 123L), lib.position(e.id))
        assertEquals(e.id, lib.find("read tides of test")?.id ?: lib.find("tides of test")?.id)
        assertTrue(lib.delete(e.id)); assertNull(lib.get(e.id)); assertTrue(lib.list().isEmpty())
    }

    @Test fun sentenceFilesAreRandomAccess() {
        val lib = Library(Files.createTempDirectory("lib").toFile())
        val w = lib.Writer("x.txt")
        w.chapter(Chapter("Chapter 1", Kind.CHAPTER, "The first sentence is right here. The second one — “quoted” — follows it.\n\nA new paragraph begins now. Ünïcödé works too."))
        val e = w.finish("T", "", "txt")
        lib.sentences(e.id, 0)!!.use { s ->
            assertEquals(5, s.size); assertEquals(5, e.chapters[0].sentences)
            assertEquals("", s[2]); assertEquals("Ünïcödé works too.", s[4]); assertEquals("The second one — “quoted” — follows it.", s[1])
        }
        assertTrue(lib.root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun commands() {
        assertEquals(BookCmd.Read("treasure island"), BookCommands.parse("Read me Treasure Island", false))
        assertEquals(BookCmd.Read(null), BookCommands.parse("continue reading", false))
        assertEquals(null, BookCommands.parse("read me the news", false))
        assertEquals(null, BookCommands.parse("stop", false))                      // not reading: the normal "stop"
        assertEquals(BookCmd.Stop, BookCommands.parse("stop", true))
        assertEquals(BookCmd.Pause, BookCommands.parse("pause", true))
        assertEquals(BookCmd.Resume, BookCommands.parse("resume", true))
        assertEquals(BookCmd.NextChapter, BookCommands.parse("next chapter please", true))
        assertEquals(BookCmd.PrevChapter, BookCommands.parse("previous chapter", true))
        assertEquals(BookCmd.Back(3), BookCommands.parse("go back", true))
        assertEquals(BookCmd.Faster, BookCommands.parse("faster", true)); assertEquals(BookCmd.Slower, BookCommands.parse("slow down", true))
        assertEquals(BookCmd.GoTo(12), BookCommands.parse("go to chapter twelve", true))
        assertEquals(BookCmd.GoTo(27), BookCommands.parse("chapter 27", false))
        assertEquals(BookCmd.Sleep(30), BookCommands.parse("stop reading in thirty minutes", true))
        // t495u: stop ≠ pause; unload
        assertEquals(BookCmd.Stop, BookCommands.parse("stop reading", false)); assertEquals(BookCmd.Stop, BookCommands.parse("Stop reading, please", true))
        assertEquals(BookCmd.Pause, BookCommands.parse("pause reading", true))
        assertEquals(BookCmd.Unload(false), BookCommands.parse("unload the book", false)); assertEquals(BookCmd.Unload(false), BookCommands.parse("Unload my book.", true))
        assertEquals(BookCmd.Unload(true), BookCommands.parse("unload everything", false)); assertEquals(BookCmd.Unload(true), BookCommands.parse("free all the models", false))
        assertEquals(BookCmd.Recap, BookCommands.parse("what just happened?", true))
        assertEquals("Robert Louis Stevenson: Treasure Island", BookCommands.bestTitle("treasure island", listOf("Other Book", "Robert Louis Stevenson: Treasure Island")))
        assertNull(BookCommands.bestTitle("the hobbit", listOf("Robert Louis Stevenson: Treasure Island")))
    }

    @Test fun percentThroughBook() {
        val ch = listOf(Library.ChapterInfo("Front", Kind.FRONT, 10, "c0", 5), Library.ChapterInfo("One", Kind.CHAPTER, 100, "c1", 40), Library.ChapterInfo("Two", Kind.CHAPTER, 100, "c2", 60))
        val e = Library.Entry("b", "B", "", "txt", "", 0, ch)
        assertEquals(0, e.percent(null)); assertEquals(0, e.percent(Library.Position(1, 0, 0)))
        assertEquals(50, e.percent(Library.Position(2, 10, 0)))
        assertEquals(20, e.percent(Library.Position(1, 20, 0))); assertEquals(100, e.percent(Library.Position(2, 0, 0, finished = true)))
    }
}
