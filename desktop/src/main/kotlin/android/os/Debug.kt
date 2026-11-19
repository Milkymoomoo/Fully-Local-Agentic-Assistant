package android.os

/** Desktop stand-in: "native heap" = the app's tracked native buffers (threed.NativeMem) — the JVM can't see malloc. */
object Debug {
    @JvmStatic fun getNativeHeapAllocatedSize(): Long = runCatching { app.belle.assistant.threed.NativeMem.live.get() }.getOrDefault(0L)
    /** Whole-process resident memory in kB (Android's PSS is the closest thing to this on a PC). */
    @JvmStatic fun getPss(): Long = app.belle.desktop.platform.ProcMem.rssKb()
    @JvmStatic fun threadCpuTimeNanos(): Long = java.lang.management.ManagementFactory.getThreadMXBean().currentThreadCpuTime
    @JvmStatic fun getNativeHeapSize(): Long = getNativeHeapAllocatedSize()
}
