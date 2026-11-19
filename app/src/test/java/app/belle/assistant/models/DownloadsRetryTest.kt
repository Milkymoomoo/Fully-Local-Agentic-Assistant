package app.belle.assistant.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One-tap downloads retry transient failures on their own before showing Failed / Retry. */
class DownloadsRetryTest {
    @Test fun retriesAFewTimesWithGrowingCappedBackoff() {
        assertTrue(Downloads.AUTO_RETRIES in 3..6)
        assertEquals(2000L, Downloads.retryDelayMs(0))
        assertEquals(4000L, Downloads.retryDelayMs(1))
        assertEquals(16_000L, Downloads.retryDelayMs(3))
        assertEquals(60_000L, Downloads.retryDelayMs(8))
        assertEquals(60_000L, Downloads.retryDelayMs(1000))
    }
}
