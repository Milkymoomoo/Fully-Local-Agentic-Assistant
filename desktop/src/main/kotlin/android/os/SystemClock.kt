package android.os

/** Desktop stand-in for android.os.SystemClock. */
object SystemClock {
    private val t0 = System.nanoTime()
    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - t0) / 1_000_000
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - t0
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
}
