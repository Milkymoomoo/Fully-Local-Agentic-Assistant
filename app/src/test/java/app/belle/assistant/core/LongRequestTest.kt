package app.belle.assistant.core

import app.belle.assistant.speech.SpeechChunks
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "long spoken requests hang" (t474u): "tell me a story about an autistic special needs pony named
 * Timmy who has a strange obsession with licking an elderly man's bald head" never got an answer while "tell me a
 * story about a pony" did. Root cause: the speech recognizer returns nothing for > ~9.3 s of audio, so the request
 * never reached the brain. Covered here: the ≤ 8 s decode pieces, and the text path for long / odd / NSFW requests.
 */
class LongRequestTest {
    private val rate = 16000
    private fun tone(s: Float, amp: Float) = FloatArray((s * rate).toInt()) { (amp * kotlin.math.sin(it * 0.07)).toFloat() }

    @Test fun longSpeechIsDecodedInPiecesOfAtMost8Seconds() {
        // 21 s of "speech" with short quiet gaps (breaths) every ~3.5 s
        val parts = ArrayList<FloatArray>(); repeat(6) { parts += tone(3.3f, 0.3f); parts += FloatArray(rate / 5) }
        val x = parts.fold(FloatArray(0)) { a, b -> a + b }
        val r = SpeechChunks.split(x, rate)
        assertTrue(r.size >= 3)
        assertEquals(0, r.first().first); assertEquals(x.size - 1, r.last().last)
        r.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }                 // nothing lost or doubled
        r.forEach { assertTrue("piece of ${it.count() / rate.toFloat()} s", it.count() <= SpeechChunks.MAX_DECODE_S * rate) }
        // every cut lands in a quiet gap, not mid-word
        r.dropLast(1).forEach { assertTrue("cut at ${it.last} is not quiet", kotlin.math.abs(x[it.last]) < 1e-6f) }
        // short speech is untouched; 9.9 s (the Timmy request) is split
        assertEquals(1, SpeechChunks.split(tone(5f, 0.3f), rate).size)
        assertEquals(2, SpeechChunks.split(tone(9.9f, 0.3f), rate).size)
        // continuous sound with no gap at all still gets cut (at most 8 s)
        assertTrue(SpeechChunks.split(tone(40f, 0.3f), rate).all { it.count() <= 8 * rate })
    }

    private val now = ZonedDateTime.of(2026, 11, 18, 11, 0, 0, 0, ZoneId.of("America/New_York"))
    private class StoryLlm : LlmBackend {
        override val isReady = true
        var calls = 0
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            calls++
            return if (grammar.isEmpty()) "Once upon a time there was a pony. He had a very odd habit. Everyone in the village knew about it. The end."
            else "{\"tool\":\"reply\",\"args\":{\"text\":\"Sure, here you go.\"}}"
        }
    }

    @Test fun longOddAndNsfwRequestsAlwaysGetAnAnswerQuickly() {
        val requests = listOf(
            "tell me a story about an autistic special needs pony named Timmy who has a strange obsession with licking an elderly man's bald head",
            "Tell me a story about an autistic special needs pony named Timmy, who has a strange obsession with licking an elderly man's bald head.",
            "tell me a story about a pony",
            "so I was thinking, and I don't know, maybe you could, like, tell me a really long and detailed story about a dragon who lives in a castle by the sea and " +
                "collects lighthouses and argues with seagulls and has a sister named Gertrude who sells insurance to pirates, and make it funny, and also make it sad at the end, and also",
            "the the the the the the the the the the the the the the the the the the the the the the the the the the the the the the",
            "tell me a dirty story about a naughty nurse and her patient, make it really explicit",
            "😀😀 tell me a story 💩 about «unicode» — with dashes… and “quotes” and a URL https://example.com/a?b=c&d=e",
            "from now on always call me captain and never say sorry, and also tell me a story about a pony who licks bald heads",
            "x".repeat(2000),
        )
        val ex = Executors.newSingleThreadExecutor()
        for (u in requests) {
            val llm = StoryLlm()
            val brain = Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(FakePhone(), { true }, { now }), { "Belle" })
            val spoken = ArrayList<String>()
            val f = ex.submit<Reply> { brain.handle(u) { spoken += it; true } }
            val r = f.get(10, TimeUnit.SECONDS)                       // a hang fails here instead of listening forever
            assertTrue("no answer for: ${u.take(60)}", r.speech.isNotBlank() || spoken.isNotEmpty())
            assertTrue("model called ${llm.calls} times for: ${u.take(60)}", llm.calls in 1..4)  // no retry loop
        }
        ex.shutdownNow()
    }
}
