package app.belle.assistant.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs work on its own thread with a hard time limit, so a reply can never leave the assistant "thinking" forever.
 * On timeout [onTimeout] is called (it cancels the model), the work gets [graceMs] to return, and if its thread is
 * still stuck it is abandoned and a fresh thread serves the next call — later turns never queue behind a hung one.
 */
class TimedCall(private val name: String = "belle-think", private val graceMs: Long = 5_000) {
    @Volatile private var exec: ExecutorService = newExec()
    private fun newExec() = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }

    sealed class Result<T> {
        data class Ok<T>(val value: T) : Result<T>()
        class TimedOut<T>(val threadReplaced: Boolean) : Result<T>()
        data class Failed<T>(val error: Throwable) : Result<T>()
    }

    /**
     * [limitMs] is the longest the work may go without progress: [progress] returns the last time it made progress
     * (a streamed story keeps moving it), so long work that keeps producing is never cut, while a stall is.
     * [hardCapMs] bounds the whole call regardless (runaway-only).
     */
    fun <T> run(limitMs: Long, onTimeout: () -> Unit, progress: () -> Long = { 0L }, hardCapMs: Long = Long.MAX_VALUE, block: () -> T): Result<T> {
        val start = System.currentTimeMillis()
        val job = exec.submit<T> { block() }
        return try {
            while (true) {
                val now = System.currentTimeMillis()
                val deadline = minOf(maxOf(start, progress()) + limitMs, if (hardCapMs == Long.MAX_VALUE) Long.MAX_VALUE else start + hardCapMs)
                if (now >= deadline) throw TimeoutException()
                try { return Result.Ok(job.get(minOf(1000L, deadline - now), TimeUnit.MILLISECONDS)) } catch (_: TimeoutException) {}
            }
            @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
        } catch (e: TimeoutException) {
            runCatching(onTimeout)
            val finished = runCatching { job.get(graceMs, TimeUnit.MILLISECONDS) }.isSuccess
            if (!finished) reset()
            Result.TimedOut(!finished)
        } catch (e: java.util.concurrent.ExecutionException) {
            Result.Failed(e.cause ?: e)
        } catch (e: Throwable) {
            Result.Failed(e)
        }
    }

    /** Abandons the current thread (interrupting it) and starts a fresh one. */
    fun reset() { val old = exec; exec = newExec(); old.shutdownNow() }
}
