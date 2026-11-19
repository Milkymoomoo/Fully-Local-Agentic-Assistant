package app.belle.assistant.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolsAndWakeWordTest {
    @Test fun grammarCoversEveryTool() {
        val g = Tools.grammar()
        for (t in Tools.ALL) assertTrue(t.name, g.contains("\\\"${t.name}\\\""))
        assertTrue(g.startsWith("root ::="))
    }

    @Test fun parserValidatesSchema() {
        assertNotNull(LlmOutputParser.parse("{\"tool\":\"set_timer\",\"args\":{\"seconds\":\"60\",\"label\":\"\"}}"))
        assertNull(LlmOutputParser.parse("{\"tool\":\"set_timer\",\"args\":{\"seconds\":\"soon\"}}"))
        assertNull(LlmOutputParser.parse("{\"tool\":\"flashlight\",\"args\":{\"state\":\"purple\"}}"))
        assertNull(LlmOutputParser.parse("not json"))
        val a = LlmOutputParser.parse("junk {\"tool\":\"reply\",\"args\":{\"text\":\"Hi there\"}} junk")!!
        assertEquals("Hi there", a.arg("text"))
    }

    /** Writes the exact prompt and grammar to build/ so tools/host_llm can test real models with them. */
    @Test fun dumpPromptAndGrammar() {
        val dir = File("build/llm-test").apply { mkdirs() }
        File(dir, "system.txt").writeText(Tools.systemPrompt("Belle"))
        File(dir, "grammar.gbnf").writeText(Tools.grammar())
    }

    private fun tokenizer(): UnigramTokenizer =
        UnigramTokenizer(javaClass.classLoader!!.getResourceAsStream("bpe.vocab")!!.bufferedReader().readLines())

    /** Our Kotlin Viterbi must match sentencepiece exactly, or wake words won't match the model. */
    @Test fun tokenizerMatchesSentencepiece() {
        val tok = tokenizer()
        val fixture = JSONArray(javaClass.classLoader!!.getResourceAsStream("bpe_fixture.json")!!.bufferedReader().readText())
        var mismatches = 0
        for (i in 0 until fixture.length()) {
            val o = fixture.getJSONObject(i)
            val expected = (0 until o.getJSONArray("tokens").length()).map { o.getJSONArray("tokens").getString(it) }
            val got = tok.encode(o.getString("text"))
            if (got != expected) { mismatches++; println("MISMATCH ${o.getString("text")}: $got vs $expected") }
        }
        assertEquals(0, mismatches)
    }

    @Test fun keywordLines() {
        val lines = WakeWord.keywordLines(tokenizer(), listOf("hey belle"), sensitivity = 0.5f)
        assertEquals(listOf("▁HE Y ▁BE LL E :1.5 #0.25 @HEY_BELLE"), lines)
        // unlimited phrases, all active; OK/OKAY variants added automatically
        val many = WakeWord.keywordLines(tokenizer(), listOf("hey ellie", "okay belle", "yo computer", "Miss Scarlett!", "  "), 0.5f, mutableListOf())
        assertEquals(5, many.size)
        assertTrue(many.any { it.endsWith("@YO_COMPUTER") })
        assertEquals(2, many.count { it.endsWith("@OKAY_BELLE") })
        val rejected = mutableListOf<String>()
        WakeWord.keywordLines(tokenizer(), listOf("123", "hey belle"), 0.5f, rejected)
        assertEquals(listOf("123"), rejected)
        assertEquals(listOf("hey dixie", "okay dixie"), WakeWord.defaultPhrases("Dixie"))
    }

    @Test fun modelRecommendations() {
        assertEquals("dolphin3-qwen2.5-1.5b", ModelCatalog.recommendedLlm(7.4).id)   // Galaxy S24 (8 GB) reports ~7.4
        assertEquals("dolphin3-llama3.2-3b", ModelCatalog.recommendedLlm(11.2).id)    // WP100 Titan (12 GB)
        assertEquals("dolphin3-qwen2.5-0.5b", ModelCatalog.recommendedLlm(3.6).id)
        assertEquals(4, ModelCatalog.recommendedThreads(8))
    }
}
