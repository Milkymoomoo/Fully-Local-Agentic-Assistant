package app.belle.assistant.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Thinking forever": a stuck reply is cut off, later turns never queue behind it, and memory work stays off the reply path. */
class HangGuardTest {
    @Test fun stuckReplyTimesOutAndCancels() {
        val t = TimedCall(graceMs = 200)
        var cancelled = false
        val release = CountDownLatch(1)
        val r = t.run(300, onTimeout = { cancelled = true; release.countDown() }) { release.await(); "late" }
        assertTrue(r is TimedCall.Result.TimedOut); assertTrue(cancelled)
        assertFalse((r as TimedCall.Result.TimedOut).threadReplaced)   // the cancel freed it
    }

    @Test fun aThreadThatIgnoresTheCancelIsReplacedSoTheNextTurnRuns() {
        val t = TimedCall(graceMs = 100)
        val never = CountDownLatch(1)
        val r = t.run(200, onTimeout = {}) { while (true) { try { never.await() } catch (_: InterruptedException) { } } }
        assertTrue((r as TimedCall.Result.TimedOut).threadReplaced)
        val t0 = System.nanoTime()
        val next = t.run(1000, onTimeout = {}) { "hello" }
        assertEquals("hello", (next as TimedCall.Result.Ok).value)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 500)
    }

    @Test fun errorsBecomeFailedNotHangs() {
        val r = TimedCall().run(1000, onTimeout = {}) { error("boom") }
        assertTrue(r is TimedCall.Result.Failed)
    }

    @Test fun longRoleplayRepliesDoNotSummarizeEveryTurn() {
        // Before: tokens > budget/2 fired after every long reply, putting a slow model call before each next reply.
        val s = ListMemoryStore(); val sum = MemorySummarizer()
        val llm = object : LlmBackend {
            var calls = 0
            override val isReady = true
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String { calls++; return "SUMMARY: they played.\nFACTS:\n- likes nurses" }
        }
        var runs = 0
        repeat(40) {
            s.addTurn(Memory.STORY, "keep going, describe it slowly", "x".repeat(400))
            if (sum.shouldSummarize(s, Memory.STORY, Brain.MAX_MEMORY_TOKENS)) { sum.run(s, Memory.STORY, llm, "Belle"); runs++ }
        }
        assertTrue("summaries: $runs", runs <= 40 / 4)
    }

    @Test fun interruptedSummaryIsPostponedNotLost() {
        val s = ListMemoryStore(); val sum = MemorySummarizer()
        repeat(12) { s.addTurn(Memory.CHAT, "hello $it", "hi $it") }
        val cancelled = object : LlmBackend { override val isReady = true
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int) = "" }   // aborted by a reply
        assertFalse(sum.run(s, Memory.CHAT, cancelled, "Belle"))
        assertEquals(12, sum.loose(s, Memory.CHAT).size)
    }

    @Test fun memoryBlockIsCappedForPhoneSpeed() {
        val l = object : LlmBackend { override val isReady = true; override val contextTokens = 8192
            override fun complete(system: String, user: String, grammar: String, maxTokens: Int) = "" }
        val b = Brain({ RuleParser(listOf("Belle")) }, { l }, ActionRouter(FakePhone()), { "Belle" })
        assertTrue(b.memoryBudget(l) <= Brain.MAX_MEMORY_TOKENS)
    }
}
