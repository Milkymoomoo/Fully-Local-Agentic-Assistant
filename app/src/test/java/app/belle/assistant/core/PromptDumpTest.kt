package app.belle.assistant.core

import java.io.File
import org.junit.Test

/** Writes the exact system prompts (every assistant is uncensored; normal and roleplay) + grammar for the host LLM harness (tools/host_llm). */
class PromptDumpTest {
    @Test fun dump() {
        val d = File(System.getProperty("java.io.tmpdir"), "belle_prompts").apply { mkdirs() }
        val persona = PersonaPrompt.defaults("Belle")
        File(d, "system.txt").writeText(Tools.fullSystemPrompt("Belle", persona))
        File(d, "grammar.gbnf").writeText(Tools.grammar())
        File(d, "system_rp.txt").writeText(Tools.fullSystemPrompt("Belle", persona, roleplay = true))
        // Worst-case memory prompts after a long conversation (one per line, newlines escaped as \n) for real-tokenizer checks.
        val words = "castle dragon road tavern knight sword moon river forest storm gold secret night fire queen".split(" ")
        val out = StringBuilder()
        for ((sys, th) in listOf(Tools.fullSystemPrompt("Belle", persona) to Memory.CHAT,
                Tools.fullSystemPrompt("Belle", persona, roleplay = true) to Memory.STORY)) {
            val s = ListMemoryStore()
            repeat(300) { i -> s.addTurn(th, "turn $i: tell me about the ${words[i % words.size]} and the ${words[(i * 7) % words.size]}, it's so ${"very ".repeat(i % 30)}interesting",
                "Oh hon, the ${words[i % words.size]} is full of ${words[(i * 3) % words.size]}s and old stories, let me tell you about it some more.") }
            s.setSummary(th, (1..120).joinToString(" ") { words[it % words.size] })
            repeat(40) { s.addNote(th, "The user likes the ${words[it % words.size]} and the ${words[(it + 5) % words.size]}") }
            for (u in listOf("what about the dragon and the castle", "tell me a secret about the queen")) {
                val b = MemoryAssembler.build(s, th, u, minOf(Brain.MAX_MEMORY_TOKENS, Memory.budget(2048, Memory.tokens(sys), Brain.MAX_REPLY_TOKENS)))
                out.append(b.text.replace("\n", "\\n")).append('\n')
            }
        }
        File(d, "mem_utt.txt").writeText(out.toString())
    }
}
