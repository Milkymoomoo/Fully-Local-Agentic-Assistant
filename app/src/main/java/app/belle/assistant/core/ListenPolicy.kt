package app.belle.assistant.core

/**
 * Every listening-timer decision in one place, so "unlimited listening" is honoured on every path.
 * Unlimited (the "Unlimited listening" switch on, or Continuous conversation on): no silence limit, no max listen time, always listen again
 * after a reply (regardless of Continuous conversation), and the watchdog restarts listening instead of ending it.
 * Only a sleep word or a manual stop ends an unlimited session. In normal modes, anything that ends listening goes
 * back to wake-word listening (never to deaf / off).
 */
object ListenPolicy {
    data class Cfg(val timeoutOn: Boolean, val timeoutSec: Int, val continuous: Boolean) {
        /** Continuous conversation counts as unlimited too: only a sleep word or a manual stop ends it. */
        val unlimited: Boolean get() = !timeoutOn || continuous
    }

    /** Silence / max-listen timer. [audioMs] = audio fed to the recognizer since listening began. */
    fun listenExpired(c: Cfg, audioMs: Long, heardSpeech: Boolean): Boolean {
        if (c.unlimited) return false
        val silence = c.timeoutSec * 1000L
        return if (!heardSpeech) audioMs > silence else audioMs > silence + 30_000
    }

    enum class After { LISTEN_AGAIN, WAKE_WORDS }
    /** After a reply was spoken (session not ended by a sleep word or manual stop). */
    fun afterReply(c: Cfg, expectsReply: Boolean): After =
        if (c.unlimited || expectsReply || c.continuous) After.LISTEN_AGAIN else After.WAKE_WORDS

    enum class Recover { NONE, RESTART_LISTENING, WAKE_WORDS }
    /** Watchdog: a dead microphone / hung recognizer while listening, or a stuck think/wake step. */
    fun watchdog(c: Cfg, listening: Boolean, busy: Boolean, sinceListenStartMs: Long, sinceProgressMs: Long, speaking: Boolean = false): Recover {
        // A reply being worked out or spoken (a long story) is never cut here while it still moves; the reply has its
        // own hard watchdog. Only a stall of minutes with no progress at all counts.
        if (speaking && sinceProgressMs <= 180_000L) return Recover.NONE
        if (busy) return if (sinceProgressMs > 180_000L) (if (c.unlimited) Recover.RESTART_LISTENING else Recover.WAKE_WORDS) else Recover.NONE
        if (!listening) return Recover.NONE
        if (c.unlimited) {
            // No time limit; only a recognizer that has made no progress for minutes is restarted (still listening).
            return if (sinceProgressMs > 10 * 60_000L) Recover.RESTART_LISTENING else Recover.NONE
        }
        val wall = c.timeoutSec * 3000L + 60_000L
        return if (sinceListenStartMs > wall && sinceProgressMs > 20_000) Recover.WAKE_WORDS else Recover.NONE
    }
}
