package android.os

/** Desktop stand-in: PCs don't kill background programs for battery; wake locks map onto KeepAwake. */
class PowerManager {
    fun isIgnoringBatteryOptimizations(pkg: String) = true
    fun newWakeLock(flags: Int, tag: String) = WakeLock(tag)
    class WakeLock(private val tag: String) {
        var isHeld = false; private set
        fun setReferenceCounted(b: Boolean) {}
        fun acquire() { isHeld = true; app.belle.desktop.platform.KeepAwake.acquire(tag) }
        fun acquire(ms: Long) = acquire()
        fun release() { isHeld = false; app.belle.desktop.platform.KeepAwake.release(tag) }
    }
    companion object { const val PARTIAL_WAKE_LOCK = 1 }
}
