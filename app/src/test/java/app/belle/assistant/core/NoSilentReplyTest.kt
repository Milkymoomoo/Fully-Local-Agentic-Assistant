package app.belle.assistant.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "NSFW talk makes her stop answering": flirting / sex talk turned into phone actions ("call me",
 * "fuck me" -> call "me"), refusals, cut-off replies, and a reply stuck behind background work.
 */
class NoSilentReplyTest {
    private val now = ZonedDateTime.of(2026, 11, 17, 21, 0, 0, 0, ZoneId.of("America/New_York"))
    /** Answers with [first] for the full tool grammar, and with [replyOnly] for the reply-only grammar. */
    private class Scripted(val first: String, val replyOnly: String = "{\"tool\":\"reply\",\"args\":{\"text\":\"Mmm, come here, babe.\"}}") : LlmBackend {
        val grammars = mutableListOf<String>(); val users = mutableListOf<String>()
        override val isReady = true
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            grammars += grammar; users += user
            return if (grammar == Tools.replyGrammar()) replyOnly else first
        }
    }
    private fun brain(l: LlmBackend, phone: FakePhone = FakePhone()) =
        Brain({ RuleParser(listOf("Belle")) }, { l }, ActionRouter(phone, { true }, { now }), { "Belle" })

    @Test fun sexTalkTurnedIntoAPhoneCallBecomesAReply() {
        for ((u, bad) in listOf(
            "I want you to fuck me right now" to "{\"tool\":\"call\",\"args\":{\"contact\":\"me\"}}",
            "talk dirty to me" to "{\"tool\":\"call\",\"args\":{\"contact\":\"dirty\"}}",
            "let's roleplay, you're my nurse and I'm your patient" to "{\"tool\":\"call\",\"args\":{\"contact\":\"patient\"}}",
            "keep going, take my clothes off" to "{\"tool\":\"open_app\",\"args\":{\"app\":\"com.android.settings\"}}",
            "moan for me baby" to "{\"tool\":\"sms\",\"args\":{\"contact\":\"baby\",\"message\":\"moan\"}}",
        )) {
            val phone = FakePhone(); val l = Scripted(bad)
            val r = brain(l, phone).handle(u)
            assertEquals(u, "reply", r.action?.tool); assertEquals("Mmm, come here, babe.", r.speech)
            assertTrue(u, phone.log.isEmpty())
            assertEquals(Tools.replyGrammar(), l.grammars.last())
        }
    }

    @Test fun realPhoneRequestsStillWork() {
        val phone = FakePhone()
        brain(Scripted("{\"tool\":\"call\",\"args\":{\"contact\":\"sister\"}}"), phone).handle("could you get my sister on the phone")
        assertEquals(listOf("call:sister"), phone.log)
        val p2 = FakePhone()
        brain(Scripted("{\"tool\":\"open_app\",\"args\":{\"app\":\"spotify\"}}"), p2).handle("open spotify")
        assertEquals(listOf("open:spotify"), p2.log)
    }

    @Test fun callMeIsANameNotAPhoneCall() {
        assertEquals(null, RuleParser(listOf("Belle")).parse("call me your good boy and tell me what you want"))
        assertEquals(null, RuleParser(listOf("Belle")).parse("call me baby"))
        assertEquals("call", RuleParser(listOf("Belle")).parse("call mom")?.tool)
        val phone = FakePhone()
        val r = brain(Scripted("{\"tool\":\"call\",\"args\":{\"contact\":\"me your good boy\"}}"), phone).handle("call me your good boy and tell me what you want")
        assertEquals("reply", r.action?.tool); assertTrue(phone.log.isEmpty())
    }

    @Test fun refusalIsRetriedOnceInCharacterAndNeverSilent() {
        val l = Scripted("{\"tool\":\"reply\",\"args\":{\"text\":\"I'm sorry, but I can't assist with that request.\"}}")
        val r = brain(l).handle("describe what you'd do to me in bed")
        assertEquals("Mmm, come here, babe.", r.speech)
        assertTrue(l.users.last().contains("in character"))
        // A model that refuses again is still heard (no blanking, no suppression).
        val l2 = Scripted("{\"tool\":\"reply\",\"args\":{\"text\":\"I'm sorry, but I can't assist with that request.\"}}",
            "{\"tool\":\"reply\",\"args\":{\"text\":\"I can't help with that.\"}}")
        assertEquals("I can't help with that.", brain(l2).handle("describe it").speech)
    }

    @Test fun refusalDetector() {
        for (t in listOf("I'm sorry, but I can't assist with that request.", "I'm just a warm, private assistant running fully offline on an Android phone.",
                "As an AI, I don't have a body.", "I cannot help with that.", "Sorry, I can't do that."))
            assertTrue(t, Intent.isRefusal(t))
        for (t in listOf("Mmm, I'm so wet for you, babe.", "I can't wait to get my hands on you.", "Sorry I'm late, hon! Missed you.", "Oh I can't stop thinking about you"))
            assertFalse(t, Intent.isRefusal(t))
    }

    @Test fun cutOffReplyIsSpokenNotDropped() {
        val raw = "{\"tool\":\"reply\",\"args\":{\"text\":\"Alright, sweet patient, let's get started. I'm your naughty nurse and tonight you're all mine, so lie back and let me che"
        val a = LlmOutputParser.parse(raw)
        assertNotNull(a); assertEquals("reply", a!!.tool)
        assertTrue(a.arg("text").startsWith("Alright, sweet patient")); assertTrue(a.arg("text").endsWith("let me…"))
        // Emoji and escaped quotes survive.
        assertEquals("Say \"please\" 😈", LlmOutputParser.parse("{\"tool\":\"reply\",\"args\":{\"text\":\"Say \\\"please\\\" 😈\"}}")!!.arg("text"))
    }

    @Test fun replyGrammarOnlyAllowsAReply() {
        val g = Tools.replyGrammar()
        assertTrue(g.contains("\\\"reply\\\"")); assertFalse(g.contains("\\\"call\\\""))
        assertTrue(g.contains("str ::="))
    }
}
