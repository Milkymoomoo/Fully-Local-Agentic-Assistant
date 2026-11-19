package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Desktop stand-in for android.os.Handler: post / postDelayed / removeCallbacks on its [Looper]'s thread. */
open class Handler(val looper: Looper = Looper.getMainLooper()) {
    private val pending = ConcurrentHashMap<Runnable, MutableList<ScheduledFuture<*>>>()
    fun post(r: Runnable): Boolean = postDelayed(r, 0)
    fun postDelayed(r: Runnable, ms: Long): Boolean {
        val box = arrayOfNulls<ScheduledFuture<*>>(1)
        val f = try { looper.schedule({ box[0]?.let { b -> pending[r]?.remove(b) }; r.run() }, ms.coerceAtLeast(0)) }
            catch (_: java.util.concurrent.RejectedExecutionException) { return false }
        box[0] = f; pending.getOrPut(r) { java.util.Collections.synchronizedList(ArrayList()) }.add(f); return true
    }
    fun removeCallbacks(r: Runnable) { pending.remove(r)?.toList()?.forEach { it.cancel(false) } }
    fun removeCallbacksAndMessages(token: Any?) { pending.keys.toList().forEach { removeCallbacks(it) } }
}

/** Desktop stand-in for android.os.HandlerThread. */
class HandlerThread(private val name: String, priority: Int = 0) {
    private var l: Looper? = null
    fun start() { l = Looper(name) }
    val looper: Looper get() = l ?: error("HandlerThread $name not started")
    fun quitSafely(): Boolean { l?.quitSafely(); return true }
    fun quit(): Boolean { l?.quit(); return true }
}
