package app.belle.assistant.llm

import android.util.Log
import app.belle.assistant.core.LlmBackend
import java.io.File

/**
 * llama.cpp running on the phone's CPU through our small JNI bridge (app/src/main/cpp).
 *
 * One llama context serves replies and background work (memory summaries, prompt warm-up). Replies always win:
 * [background] marks low-priority work, and [preempt] (called before a reply) cancels it inside llama.cpp so the
 * reply never waits behind it. [cancel] stops whatever is running (the reply watchdog uses it).
 */
class LlamaEngine : LlmBackend {
    @Volatile private var handle = 0L
    private val freeLock = Any()
    var loadedFile: String = ""
        private set

    override val isReady: Boolean get() = handle != 0L
    override var contextTokens: Int = 2048
        private set
    override val modelName: String get() = loadedFile

    @Volatile private var bgRunning = false
    /** A reply is being worked out: background calls give up at once instead of starting. */
    @Volatile private var replyActive = false
    private val isBg = ThreadLocal<Boolean>()
    @Volatile var lastError: String = ""
        private set

    @Synchronized
    fun load(file: File, threads: Int, ctx: Int = DEFAULT_CTX): Boolean {
        unload()
        val t0 = System.currentTimeMillis()
        val h = nativeLoad(file.path, threads, ctx)
        contextTokens = ctx
        loadedFile = if (h != 0L) file.name else ""
        handle = h
        Log.i("BelleLlm", "load ${file.name} threads=$threads ok=${h != 0L} in ${System.currentTimeMillis() - t0} ms")
        return h != 0L
    }

    @Synchronized
    fun unload() {
        val h = handle
        handle = 0L; loadedFile = ""
        if (h != 0L) synchronized(freeLock) { nativeFree(h) }
    }

    @Synchronized
    override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
        val h = handle
        if (h == 0L) return ""
        val bg = isBg.get() == true
        if (bg) { if (replyActive) return "" } else nativeReset(h)
        val t0 = System.currentTimeMillis()
        val out = String(nativeComplete(h, system.toByteArray(), user.toByteArray(), grammar.toByteArray(), maxTokens), Charsets.UTF_8)
        Log.i("BelleLlm", "complete in ${System.currentTimeMillis() - t0} ms${if (bg) " (background)" else ""}: $out")
        return out
    }

    /** Calls back into Kotlin from the native generation loop (whole UTF-8 characters per piece). */
    private class Sink(val f: (String) -> Boolean) {
        @Suppress("unused") fun onText(b: ByteArray): Boolean = f(String(b, Charsets.UTF_8))
    }

    @Synchronized
    override fun stream(system: String, user: String, maxTokens: Int, temp: Float, capMs: Long, onText: (String) -> Boolean): LlmBackend.Streamed {
        val h = handle
        if (h == 0L) return LlmBackend.Streamed("", false)
        if (isBg.get() == true && replyActive) return LlmBackend.Streamed("", false)
        if (isBg.get() != true) nativeReset(h)
        val t0 = System.currentTimeMillis()
        val out = String(nativeStream(h, system.toByteArray(), user.toByteArray(), maxTokens, temp, capMs.toDouble(), Sink(onText)), Charsets.UTF_8)
        val hit = nativeHitLimit(h)
        Log.i("BelleLlm", "stream in ${System.currentTimeMillis() - t0} ms, ${out.length} chars${if (hit) " (context/limit reached)" else ""}")
        return LlmBackend.Streamed(out, hit)
    }

    /** Runs low-priority model work that a reply may interrupt at any time (its output is then just discarded). */
    fun <T> background(block: () -> T): T {
        bgRunning = true; isBg.set(true)
        try { return block() } finally { bgRunning = false; isBg.set(false) }
    }

    /** A reply is about to run: stop background work now instead of waiting for it, and keep it off until [replyDone]. */
    fun preempt() { replyActive = true; if (bgRunning) cancel() }
    fun replyDone() { replyActive = false }

    /** Stops the running completion (any thread). */
    fun cancel() { synchronized(freeLock) { val h = handle; if (h != 0L) nativeCancel(h) } }

    private external fun nativeLoad(path: String, threads: Int, ctx: Int): Long
    private external fun nativeComplete(h: Long, system: ByteArray, user: ByteArray, grammar: ByteArray, maxTokens: Int): ByteArray
    private external fun nativeStream(h: Long, system: ByteArray, user: ByteArray, maxTokens: Int, temp: Float, genCapMs: Double, sink: Any): ByteArray
    private external fun nativeHitLimit(h: Long): Boolean
    private external fun nativeCancel(h: Long)
    private external fun nativeReset(h: Long)
    private external fun nativeFree(h: Long)

    companion object {
        /** Room for the system prompt with all four persona sections filled, the memory block and a long reply. */
        const val DEFAULT_CTX = 4096
        init { System.loadLibrary("belle_llm") }
    }
}
