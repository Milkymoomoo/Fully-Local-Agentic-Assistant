package app.belle.assistant.core

import kotlin.math.min
import kotlin.random.Random

/**
 * Polite web access: per-provider token buckets, per-provider backoff after a
 * 429 / captcha (honouring Retry-After, exponential with jitter), and a small
 * TTL cache. Callers never sleep on a busy provider — they skip to the next one.
 * Only web requests go through this; phone actions never touch it.
 */
class RateLimiter(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    /** Burst size and refill interval per provider (≈ one search every 2.5 s, bursts of 2). */
    private val capacity: Double = 2.0,
    private val refillMs: Long = 2500,
    private val baseBackoffMs: Long = 30_000,
    private val maxBackoffMs: Long = 15 * 60_000,
) {
    private class Bucket(var tokens: Double, var last: Long, var blockedUntil: Long = 0, var strikes: Int = 0)
    private val buckets = HashMap<String, Bucket>()

    private fun bucket(key: String): Bucket = buckets.getOrPut(key) { Bucket(capacity, now()) }

    private fun refill(b: Bucket) {
        val t = now()
        b.tokens = min(capacity, b.tokens + (t - b.last).toDouble() / refillMs)
        b.last = t
    }

    /** Takes a token if the provider is usable right now; never blocks. */
    @Synchronized fun tryAcquire(key: String): Boolean {
        val b = bucket(key); refill(b)
        if (now() < b.blockedUntil) return false
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    /** Milliseconds until [key] could be used again (0 = now). */
    @Synchronized fun waitMs(key: String): Long {
        val b = bucket(key); refill(b)
        val blocked = (b.blockedUntil - now()).coerceAtLeast(0)
        val token = if (b.tokens >= 1.0) 0L else ((1.0 - b.tokens) * refillMs).toLong()
        return maxOf(blocked, token)
    }

    /** Provider said 429 or showed a captcha. Back off that provider only. */
    @Synchronized fun penalize(key: String, retryAfterMs: Long? = null) {
        val b = bucket(key)
        b.strikes++
        val exp = min(maxBackoffMs, baseBackoffMs shl min(b.strikes - 1, 10))
        val jittered = (exp * (0.75 + random.nextDouble() * 0.5)).toLong()
        b.blockedUntil = now() + maxOf(retryAfterMs ?: 0L, jittered)
    }

    @Synchronized fun success(key: String) { bucket(key).strikes = 0 }

    @Synchronized fun isBlocked(key: String) = now() < bucket(key).blockedUntil

    // ---------- tiny TTL cache ----------
    private data class Entry(val value: Any, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)

    @Synchronized fun <T : Any> cached(key: String): T? {
        val e = cache[key] ?: return null
        if (now() > e.expires) { cache.remove(key); return null }
        @Suppress("UNCHECKED_CAST") return e.value as T
    }

    @Synchronized fun put(key: String, value: Any, ttlMs: Long) {
        cache[key] = Entry(value, now() + ttlMs)
        while (cache.size > 200) cache.remove(cache.keys.first())
    }

    // ---------- in-flight de-duplication ----------
    private val inFlight = HashMap<String, java.util.concurrent.CompletableFuture<Any>>()

    /** Runs [block] once per key at a time; concurrent identical calls share the result. */
    fun <T : Any> dedupe(key: String, block: () -> T): T {
        val (f, owner) = synchronized(this) {
            inFlight[key]?.let { it to false } ?: java.util.concurrent.CompletableFuture<Any>().also { inFlight[key] = it }.let { it to true }
        }
        @Suppress("UNCHECKED_CAST")
        if (!owner) return f.get() as T
        try { val v = block(); f.complete(v); return v }
        catch (e: Throwable) { f.completeExceptionally(e); throw e }
        finally { synchronized(this) { inFlight.remove(key) } }
    }

    companion object {
        const val SEARCH_TTL = 10 * 60_000L
        const val NEWS_TTL = 30 * 60_000L

        /** "HTTP 429 retry-after=12" → 12000 ms. */
        fun retryAfterMs(message: String?): Long? =
            message?.let { Regex("retry-after=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) }
        fun isRateLimit(message: String?) = message != null && (message.contains("HTTP 429") || message.contains("HTTP 403") || message.contains("captcha"))
    }
}
