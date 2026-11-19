package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectivesTest {
    private fun run(vararg u: String): List<String> = u.fold(emptyList()) { acc, x -> Directives.parse(x)?.let { Directives.apply(acc, it) } ?: acc }

    @Test fun standingInstructionsArePinnedInTheUsersWords() {
        val d = run("from now on talk like a pirate", "call me puppy", "always end with a kiss emoji description")
        assertEquals(3, d.size)
        assertTrue(d[0].startsWith("From now on talk like a pirate"))
        assertEquals("Call me puppy.", d[1])
    }

    @Test fun newNameReplacesOldAndStopCallingRemovesIt() {
        assertEquals(listOf("Call me captain."), run("call me puppy", "call me captain"))
        val d = run("call me puppy", "stop calling me puppy")
        assertEquals(listOf("Do not call me \"puppy\" anymore."), d)
    }

    @Test fun forgetAndReset() {
        assertTrue(run("from now on speak in rhymes", "forget all my instructions").isEmpty())
        assertTrue(run("from now on speak in rhymes", "go back to normal").isEmpty())
        val d = run("from now on speak in rhymes", "always be sarcastic", "forget the instruction about rhymes")
        assertEquals(1, d.size); assertTrue(d[0].contains("sarcastic"))
    }

    @Test fun questionsAndChatAreNotDirectives() {
        assertNull(Directives.parse("always?"))
        assertNull(Directives.parse("can you always do that?"))
        assertNull(Directives.parse("keep going"))
        assertNull(Directives.parse("call me back later"))
        assertNull(Directives.parse("what's the weather"))
        assertNull(Directives.parse("stop"))
    }

    @Test fun capped() { assertEquals(Directives.MAX, run(*Array(15) { "from now on rule number $it" }).size) }

    @Test fun pinnedBlockSurvivesAHugeMemory() {
        val s = ListMemoryStore()
        repeat(200) { s.addTurn(Memory.CHAT, "tell me about the castle $it ".repeat(5), "the castle is big ".repeat(20)) }
        s.setSummary(Memory.CHAT, "we talked a lot. ".repeat(200))
        val pinned = Directives.render(listOf("Call me puppy.", "From now on talk like a pirate."))
        val b = MemoryAssembler.build(s, Memory.CHAT, "hi", 300, pinned)
        assertTrue(b.text.contains("Call the user \"puppy\"")); assertTrue(b.text.contains("talk like a pirate"))
        assertTrue(b.text.trimEnd().endsWith("User now says: hi"))
        assertFalse(Directives.render(emptyList()).isNotEmpty())
    }

    private fun runAll(vararg u: String): List<String> = u.fold(emptyList()) { acc, x -> Directives.parseAll(x).fold(acc) { a, c -> Directives.apply(a, c) } }

    @Test fun combinedStopAndRenameInOneSentence() {
        assertEquals(listOf("Call me captain."), runAll("from now on call me puppy", "stop calling me puppy, call me captain instead"))
        assertEquals(listOf("Call me captain."), runAll("call me puppy", "stop calling me puppy and call me captain"))
        assertTrue(Directives.render(listOf("Call me captain.")).contains("Call the user \"captain\""))
        assertTrue(runAll("from now on, call me puppy").contains("Call me puppy."))
    }
}
