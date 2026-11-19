package app.belle.assistant.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The system prompt every model gets: always uncensored for adults, the editable persona sections, context trimming. */
class PromptAssemblyTest {
    private class Capture(val failLongTurns: Boolean = false) : LlmBackend {
        val systems = mutableListOf<String>(); val users = mutableListOf<String>()
        override val isReady = true
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            systems += system; users += user
            if (failLongTurns && user.contains("Earlier in this conversation")) return ""   // "prompt too long for context"
            return "{\"tool\":\"reply\",\"args\":{\"text\":\"ok\"}}"
        }
    }
    private val now = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, ZoneId.of("America/New_York"))
    private fun brain(llm: LlmBackend, persona: PersonaPrompt = PersonaPrompt("playful dragon", "short", "call me Sam", ""), rp: Boolean = false) =
        Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" },
            persona = { persona }, roleplay = { rp })

    @Test fun adultBaselineIsAlwaysOnWithNoToggle() {
        for (rp in listOf(false, true)) for (persona in listOf(PersonaPrompt("", "", "", ""), PersonaPrompt.defaults("Belle"))) {
            val c = Capture(); brain(c, persona, rp).handle("talk dirty to me")
            val s = c.systems.first()
            assertTrue(s.contains(Tools.ADULT_CLAUSE)); assertTrue(s.contains(Tools.BASELINE_CLAUSE))
            assertTrue(s.contains("consenting adult")); assertTrue(s.contains("uncensored")); assertTrue(s.contains("Never refuse"))
            assertTrue(s.contains("never moralize"))
            assertTrue(s.startsWith(Tools.systemPrompt("Belle")))
            assertFalse(s.contains("Mature mode")); assertFalse(s.contains("turned on in settings"))
        }
    }

    @Test fun personaSectionsComeLastInOrder() {
        val s = Tools.fullSystemPrompt("Belle", PersonaPrompt("PERS", "STYLE", "ADDR", "EXTRA"), roleplay = true)
        val i = listOf(s.indexOf("Tools:"), s.indexOf(Tools.BASELINE_CLAUSE), s.indexOf(Tools.ADULT_CLAUSE), s.indexOf(Tools.ROLEPLAY_CLAUSE),
            s.indexOf("PERS"), s.indexOf("STYLE"), s.indexOf("ADDR"), s.indexOf("EXTRA"))
        assertTrue(i.toString(), i.all { it >= 0 } && i == i.sorted())
    }

    @Test fun emptySectionsAreLeftOutAndLongOnesCapped() {
        val s = Tools.fullSystemPrompt("Belle", PersonaPrompt("", "  ", "", ""))
        assertFalse(s.contains("How you respond")); assertFalse(s.contains("Who you are"))
        val big = Tools.fullSystemPrompt("Belle", PersonaPrompt("x".repeat(10_000), "y".repeat(10_000), "z".repeat(10_000), "w".repeat(10_000)))
        assertTrue(big.length < s.length + 4 * PersonaPrompt.MAX_SECTION + 400)
    }

    @Test fun defaultsFollowTheNameSoNewAndImportedAssistantsGetThem() {
        val d = PersonaPrompt.defaults("Boob Dragon")
        assertTrue(d.personality.contains("Boob Dragon")); assertTrue(d.personality.contains("dragon"))
        val b = PersonaPrompt.defaults("Nova")
        assertTrue(b.personality.contains("Nova")); assertFalse(b.personality.contains("dragon"))
        assertTrue(b.style.isNotBlank() && b.address.isNotBlank() && b.extra.isEmpty())
        assertTrue(PersonaPrompt.defaults("").personality.contains("Belle"))
    }

    @Test fun editsApplyOnTheVeryNextTurn() {
        var persona = PersonaPrompt("calm", "short", "Sam", "")
        val c = Capture()
        val b = Brain({ RuleParser(listOf("Belle")) }, { c }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" }, persona = { persona })
        b.handle("how are you")
        persona = persona.copy(extra = "Always end with a wink.")
        b.handle("and now")
        assertFalse(c.systems.first().contains("wink")); assertTrue(c.systems.last().contains("Always end with a wink."))
    }

    @Test fun contextTrimmingDropsHistoryNeverTheSystemPrompt() {
        val c = Capture(failLongTurns = true); val b = brain(c)
        b.handle("what's a good name for a cat")
        b.handle("and for a dog")                     // has history → "too long" → retried without it
        assertEquals(3, c.systems.size)
        assertTrue(c.systems.all { it.contains(Tools.ADULT_CLAUSE) && it.contains(Tools.BASELINE_CLAUSE) })
        assertEquals("and for a dog", c.users.last())
    }
}
