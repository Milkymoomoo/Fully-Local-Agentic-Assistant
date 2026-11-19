package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop stand-in: a Looper is one named single-thread scheduler. The main one IS the desktop UI thread (the AWT
 * event thread Compose draws on): work posted to it runs there, as Android runs it on its UI thread.
 */
class Looper internal constructor(name: String, private val ui: Boolean = false) {
    internal val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    @Volatile internal var thread: Thread? = null
    init { if (!ui) exec.execute { thread = Thread.currentThread() } }
    val isCurrentThread get() = if (ui) java.awt.EventQueue.isDispatchThread() else Thread.currentThread() === thread
    internal fun schedule(r: Runnable, ms: Long): ScheduledFuture<*> =
        if (!ui) exec.schedule(r, ms, TimeUnit.MILLISECONDS)
        else { val f = arrayOfNulls<ScheduledFuture<*>>(1)
            exec.schedule({ java.awt.EventQueue.invokeLater { if (f[0]?.isCancelled != true) r.run() } }, ms, TimeUnit.MILLISECONDS).also { f[0] = it } }
    fun quitSafely() { exec.shutdown() }
    fun quit() { exec.shutdownNow() }
    companion object {
        private val main by lazy { Looper("belle-main-timer", ui = true) }
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper? = if (main.isCurrentThread) main else null
    }
}
