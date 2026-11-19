package app.belle.assistant.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rolling long-term memory: token budget assembly, summarization triggers, story/chat separation, no overflow. */
class MemoryTest {
    /** A fake model with a real context limit: refuses (returns "") when system + user + reply wouldn't fit. */
    private class CtxLlm(override val contextTokens: Int = 4096, val sumOut: String? = null) : LlmBackend {
        var overflows = 0; var calls = 0; var maxUsed = 0; var lastUser = ""; var lastSystem = ""
        override val isReady = true
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            calls++
            val used = Memory.tokens(system) + Memory.tokens(user) + maxTokens
            maxUsed = maxOf(maxUsed, used)
            if (used > contextTokens) { overflows++; return "" }
            lastUser = user; lastSystem = system
            if (grammar.isEmpty()) return sumOut ?: "<think>hmm</think>SUMMARY: They chatted about many things.<|im_end|>\nFACTS:\n- The user's dog is called Biscuit\n- none"
            return "{\"tool\":\"reply\",\"args\":{\"text\":\"Sure thing, that is a lovely long answer about it.<|im_end|>\"}}"
        }
    }
    private val now = ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, ZoneId.of("America/New_York"))

    @Test fun assemblyStaysUnderBudgetWithHugeHistory() {
        val s = ListMemoryStore()
        repeat(800) { s.addTurn(Memory.CHAT, "message number $it about gardening and tomatoes " + "x".repeat(it % 300), "reply $it " + "y".repeat(200)) }
        s.setSummary(Memory.CHAT, "z".repeat(5000))
        repeat(50) { s.addNote(Memory.CHAT, "fact $it about tomatoes and gardening") }
        for (budget in listOf(0, 50, 200, 500, 900, 3000)) {
            val b = MemoryAssembler.build(s, Memory.CHAT, "what about my tomatoes", budget)
            assertTrue("budget $budget used ${b.tokens}", b.tokens <= maxOf(budget, Memory.tokens("User now says: what about my tomatoes")))
            assertTrue(b.text.endsWith("User now says: what about my tomatoes"))
        }
        val b = MemoryAssembler.build(s, Memory.CHAT, "what about my tomatoes", 900)
        assertTrue(b.hasSummary); assertTrue(b.notes > 0); assertTrue(b.recentTurns >= 1)
        assertTrue(b.text.contains("reply 799"))   // newest turn always present
    }

    @Test fun budgetComesFromContextSize() {
        assertEquals(0, Memory.budget(1024, 1200, 160))
        assertTrue(Memory.budget(4096, 1000, 160) > Memory.budget(2048, 1000, 160))
        assertTrue(Memory.budget(2048, 1000, 160) + 1000 + 160 < 2048)
    }

    @Test fun bm25FindsRelevantOldSnippet() {
        val s = ListMemoryStore()
        s.addTurn(Memory.CHAT, "my dog is called Biscuit", "What a sweet name for a dog!")
        repeat(30) { s.addTurn(Memory.CHAT, "random chat $it about weather", "ok $it") }
        val b = MemoryAssembler.build(s, Memory.CHAT, "what is my dog called again", 800)
        assertTrue(b.text, b.text.contains("Biscuit")); assertEquals(1, b.snippets)
    }

    @Test fun summarizationTriggers() {
        val s = ListMemoryStore(); val sum = MemorySummarizer(keepRecent = 4, maxLoose = 10)
        repeat(4) { s.addTurn(Memory.CHAT, "hi $it", "hello") }
        assertFalse(sum.shouldSummarize(s, Memory.CHAT, 800))
        repeat(7) { s.addTurn(Memory.CHAT, "hi", "hello") }
        assertTrue(sum.shouldSummarize(s, Memory.CHAT, 800))               // more than 10 loose turns
        val s2 = ListMemoryStore()
        repeat(8) { s2.addTurn(Memory.CHAT, "w".repeat(300), "v".repeat(300)) }
        assertTrue(sum.shouldSummarize(s2, Memory.CHAT, 800))              // loose turns over the budget
        assertTrue(sum.run(s, Memory.CHAT, CtxLlm(), assistantName = "Belle"))
        assertEquals(4, sum.loose(s, Memory.CHAT).size)
        assertEquals("They chatted about many things.", s.summary(Memory.CHAT)!!.text)
        assertEquals(listOf("The user's dog is called Biscuit"), s.notes(Memory.CHAT).map { it.text })
        assertFalse(sum.shouldSummarize(s, Memory.CHAT, 800))
    }

    @Test fun summarizerWorksWithoutAModelAndForAdultContent() {
        val s = ListMemoryStore(); val sum = MemorySummarizer()
        repeat(12) { s.addTurn(Memory.STORY, "the knight enters the tavern $it.", "The barmaid smiles at him $it.") }
        assertTrue(sum.run(s, Memory.STORY, null, assistantName = "Belle"))
        assertTrue(s.summary(Memory.STORY)!!.text.contains("knight"))
        assertTrue(sum.prompt(Memory.STORY, "Belle").contains("characters"))
        assertTrue(sum.prompt(Memory.STORY, "Belle").contains("consenting adult"))
        assertTrue(sum.prompt(Memory.CHAT, "Belle").contains("consenting adult"))
    }

    @Test fun roleplayKeepsSeparateStoryMemory() {
        val s = ListMemoryStore(); val llm = CtxLlm()
        var rp = false
        val brain = Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" },
            roleplay = { rp }, memory = { s })
        brain.handle("tell me about the ocean please")
        rp = true
        brain.handle("we sail toward the dragon island")
        assertEquals(1, s.turns(Memory.CHAT).size); assertEquals(1, s.turns(Memory.STORY).size)
        brain.handle("the dragon roars")
        assertTrue(llm.lastUser.contains("dragon island")); assertFalse(llm.lastUser.contains("ocean"))
        assertTrue(llm.lastSystem.contains(Tools.ROLEPLAY_CLAUSE))
        // Template junk never reaches memory / speech.
        assertFalse(s.turns(Memory.STORY).any { it.reply.contains("<|") })
    }

    @Test fun longConversationNeverOverflowsContext() {
        for (rp in listOf(false, true)) {
            val s = ListMemoryStore(); val llm = CtxLlm(4096); val sum = MemorySummarizer()
            lateinit var brain: Brain
            brain = Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" },
                persona = { PersonaPrompt("playful, teasing dragon who loves long stories " + "p".repeat(900), "long", "babe", "x".repeat(1200)) }, roleplay = { rp },
                memory = { s }, onRemembered = { th -> if (sum.shouldSummarize(s, th, brain.memoryBudget(llm))) sum.run(s, th, llm, "Belle") })
            repeat(300) { i -> brain.handle("turn $i: tell me more about the castle and the " + "very ".repeat(i % 40) + "long road") }
            assertEquals("rp=$rp", 0, llm.overflows)
            assertTrue(llm.maxUsed <= 4096)
            val th = if (rp) Memory.STORY else Memory.CHAT
            assertEquals(300, s.turns(th).size)
            assertNotNull(s.summary(th))
            assertTrue(sum.loose(s, th).size <= 11)
        }
    }

    @Test fun memoryOffKeepsOldBehaviour() {
        val llm = CtxLlm()
        val brain = Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" }, memory = { null })
        brain.handle("tell me a story about cats"); brain.handle("and dogs?")
        assertTrue(llm.lastUser.startsWith("Earlier in this conversation"))
    }

    @Test fun cleansTemplateText() {
        assertEquals("Hi there", ModelText.clean("<think>plan</think>Hi there<|im_end|>"))
        assertEquals("Hello", ModelText.clean("Hello<end_of_turn>"))
        assertEquals("ok", LlmOutputParser.parse("{\"tool\":\"reply\",\"args\":{\"text\":\"ok<|eot_id|>\"}}")!!.args["text"])
    }

    @Test fun requiredIntsCannotBeEmpty() {
        val g = Tools.grammar()
        assertTrue(g.contains("\"\\\"seconds\\\"\" ws \":\" ws intreq"))
        assertTrue(g.contains("intreq ::="))
    }
}
