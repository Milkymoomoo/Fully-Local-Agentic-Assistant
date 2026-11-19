package app.belle.assistant.core

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongFormTest {
    @Test fun detectsStoriesSongsPoemsButNotMedia() {
        assertEquals(LongForm.Kind.STORY, LongForm.request("tell me a story about a dragon"))
        assertEquals(LongForm.Kind.STORY, LongForm.request("Can you make up a long bedtime story for me"))
        assertEquals(LongForm.Kind.SONG, LongForm.request("sing me a song about the sea"))
        assertEquals(LongForm.Kind.SONG, LongForm.request("write a love song for me"))
        assertEquals(LongForm.Kind.POEM, LongForm.request("write a poem about my cat"))
        assertNull(LongForm.request("play a song by Queen"))
        assertNull(LongForm.request("play some music"))
        assertNull(LongForm.request("what time is it"))
        assertNull(LongForm.request("set a timer for 5 minutes"))
        assertTrue(LongForm.isContinue("keep going"))
        assertTrue(LongForm.isContinue("Continue the story please"))
        assertTrue(LongForm.isContinue("what happens next?"))
        assertFalse(LongForm.isContinue("what's the weather"))
    }

    @Test fun targetLengthFromMinutes() {
        assertEquals(750, LongForm.targetWords("tell me a 5 minute story", LongForm.Kind.STORY))
        assertEquals(300, LongForm.targetWords("a two minute story", LongForm.Kind.STORY))
        assertEquals(900, LongForm.targetWords("tell me a really long story", LongForm.Kind.STORY))
    }

    @Test fun cleanerStripsJsonWrapperAndLabels() {
        val c = StreamCleaner()
        val out = StringBuilder()
        for (p in listOf("{\"tool\":\"re", "ply\",\"args\":{\"text\":\"Once upon a time, ", "a dragon slept.\\nThe end.\"}}")) out.append(c.feed(p))
        out.append(c.finish())
        assertEquals("Once upon a time, a dragon slept.\nThe end.", out.toString())
        val d = StreamCleaner(); val o2 = StringBuilder()
        for (p in listOf("[Verse 1]\nThe sea is wide\n", "**Chorus**\nOh sail away\n")) o2.append(d.feed(p))
        o2.append(d.finish())
        assertFalse(o2.contains("[Verse")); assertFalse(o2.contains("**")); assertTrue(o2.contains("The sea is wide")); assertTrue(o2.contains("Oh sail away"))
        val e = StreamCleaner(); val o3 = e.feed("<think>plan the story</think>Hello there, friend. ") + e.finish()
        assertEquals("Hello there, friend.", o3.trim())
    }

    @Test fun splitterEmitsWholeSentencesAsTheyComplete() {
        val s = SentenceSplitter()
        assertTrue(s.feed("Once upon a time there was a dra").isEmpty())
        val a = s.feed("gon who lived by the sea. She loved")
        assertEquals(listOf("Once upon a time there was a dragon who lived by the sea."), a)
        val b = s.feed(" the waves. Every night she sang to them.")
        assertTrue(b.isEmpty())   // a very short sentence waits to be joined; the last one is held until something follows it
        assertEquals(listOf("She loved the waves. Every night she sang to them."), s.finish())
        // A run-on text without punctuation is still cut (at a space) so the voice never waits for the whole reply.
        val r = SentenceSplitter(maxChars = 50)
        assertTrue(r.feed("and then and then and then and then and then and then and then and then ").isNotEmpty())
    }

    private class FakeStreamLlm(val segments: List<String>, val hitLimitFor: Int = 0, val refuseFirst: Boolean = false) : LlmBackend {
        override val isReady = true
        override val contextTokens = 4096
        val users = mutableListOf<String>()
        var calls = 0
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int) = "{\"tool\":\"reply\",\"args\":{\"text\":\"ok\"}}"
        override fun stream(system: String, user: String, maxTokens: Int, temp: Float, capMs: Long, onText: (String) -> Boolean): LlmBackend.Streamed {
            users += user
            val i = calls++
            if (refuseFirst && i == 0) { onText("I'm sorry, but I can't help with that request. "); return LlmBackend.Streamed("I'm sorry", false) }
            val seg = segments[minOf(if (refuseFirst) i - 1 else i, segments.size - 1)]
            val sb = StringBuilder()
            for (w in seg.split(" ")) { val p = "$w "; sb.append(p); if (!onText(p)) break }
            return LlmBackend.Streamed(sb.toString(), (if (refuseFirst) i - 1 else i) < hitLimitFor)
        }
    }

    private class NoPhone : PhoneActions {
        private fun ok() = ActionResult(true, "Done.")
        override fun call(contact: String) = ok(); override fun sms(contact: String, message: String) = ok(); override fun openApp(name: String) = ok()
        override fun setAlarm(hour: Int, minute: Int, label: String) = ok(); override fun setTimer(seconds: Int, label: String) = ok()
        override fun flashlight(state: String) = ok(); override fun volume(level: String, percent: Int?, stream: String) = ok()
        override fun openSettings(panel: String) = ok(); override fun media(command: String) = ok(); override fun navigate(destination: String, mode: String) = ok()
        override fun reminder(text: String, atEpochMillis: Long) = ok(); override fun calendarEvent(title: String, startEpochMillis: Long?) = ok()
        override fun saveNote(text: String) = ok(); override fun readNotes() = emptyList<String>(); override fun deviceInfo(kind: String) = ok()
        override fun screen(op: String, target: String) = ok(); override fun webSearch(query: String) = ok(); override fun camera(mode: String) = ok()
    }

    private fun brain(llm: LlmBackend, mem: MemoryStore? = ListMemoryStore()) = Brain(rules = { RuleParser(listOf("Belle")) }, llm = { llm },
        router = ActionRouter(NoPhone(), { true }, { ZonedDateTime.now() }), assistantName = { "Belle" }, memory = { mem })

    private val part1 = "Once upon a time a red dragon lived in a castle by the sea. She counted ships every evening. One night a small boat came to her shore. Inside was a sailor who could not sleep."
    private val part2 = "The dragon flew down and listened to his worries. They talked until the sun came up. From then on he visited every night. And they lived happily ever after."

    @Test fun storyStreamsSentenceBySentenceAndContinuesWhenTheContextFills() {
        val llm = FakeStreamLlm(listOf(part1, part2), hitLimitFor = 1)
        val b = brain(llm)
        val heard = mutableListOf<String>()
        val r = b.handle("tell me a story about a dragon") { heard += it; true }
        assertTrue(r.streamed)
        assertEquals(2, llm.calls)   // segment 1 hit the limit, segment 2 continued from its tail
        assertTrue(llm.users[1].contains("Inside was a sailor who could not sleep."))
        assertTrue(heard.size >= 7)
        assertTrue(heard.last().contains("happily ever after"))
        assertTrue(r.speech.contains("castle by the sea") && r.speech.contains("happily ever after"))
        // "keep going" continues the active story from its newest part
        val r2 = b.handle("keep going") { true }
        assertTrue(r2.streamed)
        assertTrue(llm.users[2].contains("happily ever after"))
        assertTrue(llm.users[2].contains("Continue the story"))
    }

    @Test fun stoppingTheVoiceStopsTheModelAndClosesTheTurn() {
        val llm = FakeStreamLlm(listOf(part1 + " " + part2))
        val b = brain(llm)
        var n = 0
        val r = b.handle("tell me a story") { n++; n < 3 }
        assertEquals(1, llm.calls)
        assertTrue(r.speech.trim().endsWith(".") || r.speech.endsWith("…"))
        assertFalse(r.speech.contains("happily ever after"))
    }

    @Test fun refusalIsNotSpokenAndIsRetriedInCharacter() {
        val llm = FakeStreamLlm(listOf(part2), refuseFirst = true)
        val b = brain(llm)
        val heard = mutableListOf<String>()
        b.handle("tell me a dirty story") { heard += it; true }
        assertEquals(2, llm.calls)
        assertFalse(heard.any { it.contains("sorry", true) })
        assertTrue(llm.users[1].contains("without refusing"))
    }

    @Test fun noCharacterCapOnLongStories() {
        val long = (1..400).joinToString(" ") { "Sentence number $it of the long tale went on." }
        val llm = FakeStreamLlm(listOf(long))
        val r = brain(llm).handle("tell me a 20 minute story") { true }
        assertTrue(r.speech.length > 15_000)
    }

    @Test fun closeTurnEndsOnASentence() {
        val b = brain(FakeStreamLlm(listOf("x")))
        assertEquals("The dragon slept. The sea was calm.", b.closeTurn("The dragon slept. The sea was calm. And then the sail"))
        assertEquals("And then the…", b.closeTurn("And then the sail"))
    }

    @Test fun replyWatchdogExtendsWhileProgressMovesButCutsAStall() {
        val t = TimedCall(graceMs = 100)
        var last = System.currentTimeMillis()
        val r = t.run(300, onTimeout = {}, progress = { last }) {
            repeat(10) { Thread.sleep(100); last = System.currentTimeMillis() }   // 1 s of steady progress, limit 300 ms
            "done"
        }
        assertEquals("done", (r as TimedCall.Result.Ok).value)
        val stall = t.run(300, onTimeout = {}, progress = { 0L }) { Thread.sleep(2000); "late" }
        assertTrue(stall is TimedCall.Result.TimedOut)
    }

    @Test fun watchdogNeverCutsSpeech() {
        val c = ListenPolicy.Cfg(true, 8, false)
        assertEquals(ListenPolicy.Recover.NONE, ListenPolicy.watchdog(c, listening = false, busy = true, sinceListenStartMs = 0, sinceProgressMs = 170_000, speaking = true))
        assertEquals(ListenPolicy.Recover.WAKE_WORDS, ListenPolicy.watchdog(c, listening = false, busy = true, sinceListenStartMs = 0, sinceProgressMs = 200_000, speaking = true))
    }

    @Test fun speakableDropsPromptEchoLabelsAndSignOffs() {
        val u = "tell me a short erotic story about us tonight"
        assertEquals(null, LongForm.speakable("User: tell me a short erotic story about us tonight", "Belle", u, true))
        assertEquals(null, LongForm.speakable("Belle, the user's own private companion, living on their Android phone and running fully offline.", "Belle", u, false))
        assertEquals(null, LongForm.speakable("Title: A Night of Passion for Two", "Belle", u, true))
        assertEquals(null, LongForm.speakable("Belle is done. She wishes the user well and will wake up at 10:45 AM on Monday.", "Belle", u, false))
        assertEquals("The moon is full.", LongForm.speakable("Belle: The moon is full.", "Belle", u, true))
        assertEquals("And so the tale continued.", LongForm.speakable("«And so the tale continued.", "Belle", u, true))
        assertEquals("He looked up.", LongForm.speakable("He looked up.", "Belle", u, false))
    }

    @Test fun tidyRemovesHtmlAndEmoji() {
        assertEquals("He paused.", LongForm.tidy("He paused. </h2> <h2>"))
        assertEquals("How's your night going?", LongForm.tidy("How's your night going? \uD83D\uDE0A"))
        assertEquals("a < b and c > d", LongForm.tidy("a < b and c > d"))
        assertEquals("Mmm, you sound so cute...", LongForm.tidy("Mmm, you sound so cute... Standing instructions from the user (keep following them every reply until the user changes them):"))
    }
}
