package app.belle.assistant.core

import app.belle.assistant.core.ListenPolicy.After
import app.belle.assistant.core.ListenPolicy.Cfg
import app.belle.assistant.core.ListenPolicy.Recover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {
    private val unlimited = Cfg(timeoutOn = false, timeoutSec = 8, continuous = false)
    private val timed = Cfg(timeoutOn = true, timeoutSec = 8, continuous = false)

    @Test fun unlimitedNeverExpires() {
        for (ms in listOf(0L, 9_000L, 60_000L, 31 * 60_000L, 24 * 3_600_000L)) for (heard in listOf(true, false))
            assertFalse(ListenPolicy.listenExpired(unlimited, ms, heard))
    }
    @Test fun timedExpiresAfterSilence() {
        assertFalse(ListenPolicy.listenExpired(timed, 7_000, false)); assertTrue(ListenPolicy.listenExpired(timed, 8_100, false))
        assertFalse(ListenPolicy.listenExpired(timed, 30_000, true)); assertTrue(ListenPolicy.listenExpired(timed, 38_100, true))
    }
    @Test fun unlimitedAlwaysListensAgainAfterAReply() {
        assertEquals(After.LISTEN_AGAIN, ListenPolicy.afterReply(unlimited, expectsReply = false))
        assertEquals(After.WAKE_WORDS, ListenPolicy.afterReply(timed, expectsReply = false))
        assertEquals(After.LISTEN_AGAIN, ListenPolicy.afterReply(timed.copy(continuous = true), false))
        assertEquals(After.LISTEN_AGAIN, ListenPolicy.afterReply(timed, expectsReply = true))
    }
    @Test fun continuousConversationIgnoresEveryTimeLimit() {
        val cont = timed.copy(continuous = true)
        assertTrue(cont.unlimited)
        for (ms in listOf(9_000L, 60_000L, 3_600_000L)) for (heard in listOf(true, false)) assertFalse(ListenPolicy.listenExpired(cont, ms, heard))
        assertEquals(After.LISTEN_AGAIN, ListenPolicy.afterReply(cont, false))
        assertEquals(Recover.NONE, ListenPolicy.watchdog(cont, true, false, 3_600_000L, 60_000))
        assertEquals(Recover.RESTART_LISTENING, ListenPolicy.watchdog(cont, false, true, 0, 200_000))
    }
    @Test fun watchdogNeverEndsUnlimitedListening() {
        assertEquals(Recover.NONE, ListenPolicy.watchdog(unlimited, true, false, 3 * 3_600_000L, 60_000))
        assertEquals(Recover.RESTART_LISTENING, ListenPolicy.watchdog(unlimited, true, false, 3 * 3_600_000L, 11 * 60_000L))
        assertEquals(Recover.RESTART_LISTENING, ListenPolicy.watchdog(unlimited, false, true, 0, 200_000))
    }
    @Test fun timedTimeoutsReturnToWakeWordsNotOff() {
        assertEquals(Recover.WAKE_WORDS, ListenPolicy.watchdog(timed, true, false, 90_000, 25_000))
        assertEquals(Recover.NONE, ListenPolicy.watchdog(timed, true, false, 30_000, 25_000))
        assertEquals(Recover.WAKE_WORDS, ListenPolicy.watchdog(timed, false, true, 0, 200_000))
        assertEquals(Recover.NONE, ListenPolicy.watchdog(timed, false, false, 0, 10_000_000))
    }
}
