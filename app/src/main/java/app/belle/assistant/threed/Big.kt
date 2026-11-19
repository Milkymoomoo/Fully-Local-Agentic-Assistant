package app.belle.assistant.threed

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Native memory for big arrays. Android gives every app a Java heap of only 256–512 MB (the "growth limit"), however
 * much RAM the phone has: a 0.1.3 build on a 11.2 GB phone died building the surface on a 145 MB Java array. Memory from
 * here is malloc'd outside that heap (the phone's RAM is the limit) and freed as soon as a sub-step is done.
 * On the phone it comes from libbelle_ort (cpp/ort_jni.c). Where that library isn't loaded (the desktop unit tests) it
 * falls back to ByteBuffer.allocateDirect, which on a desktop JVM is outside the heap too (on Android it is NOT: ART
 * puts direct buffers on the Java heap, which is why this exists).
 */
object NativeMem {
    @JvmStatic private external fun alloc(bytes: Long): ByteBuffer?
    @JvmStatic external fun free(buf: ByteBuffer)
    val native: Boolean by lazy { runCatching { app.belle.assistant.core.NativeLibs.load("belle_ort"); alloc(8)!!.also { free(it) }; true }.getOrDefault(false) }
    /** Native bytes handed out and not yet freed (for tests and the build log). */
    val live = java.util.concurrent.atomic.AtomicLong()
    val peak = java.util.concurrent.atomic.AtomicLong()
    fun bytes(n: Long): ByteBuffer {
        require(n in 0..Int.MAX_VALUE.toLong()) { "array of $n bytes is too big for one buffer" }
        val b = if (native) alloc(n) ?: throw OutOfMemoryError("no native memory for $n bytes") else ByteBuffer.allocateDirect(n.toInt())
        live.addAndGet(n).also { v -> peak.accumulateAndGet(v, ::maxOf) }
        return b.order(ByteOrder.nativeOrder())
    }
    internal fun adopted(n: Long) { live.addAndGet(n).also { v -> peak.accumulateAndGet(v, ::maxOf) } }
    fun release(b: ByteBuffer, n: Long) { live.addAndGet(-n); if (native) free(b) }
}

/**
 * One big array (floats, ints or longs) in [NativeMem], zero-filled. [close] frees it; using it after that is a bug
 * (on the phone, a crash), so every owner closes it in a `use` or `finally` once the next sub-step has its own copy.
 */
class Big private constructor(val type: Int, val size: Int, buf: ByteBuffer?) : AutoCloseable {
    private var b: ByteBuffer? = buf
    val bytes: ByteBuffer get() = b ?: error("big array used after it was freed")
    val f: FloatBuffer by lazy { check(type == F); bytes.asFloatBuffer() }
    val i: IntBuffer by lazy { check(type == I); bytes.asIntBuffer() }
    val l: LongBuffer by lazy { check(type == L); bytes.asLongBuffer() }
    val byteSize: Long get() = size.toLong() * (if (type == L) 8 else 4)

    override fun close() { val x = b ?: return; b = null; NativeMem.release(x, byteSize) }

    fun toFloatArray() = FloatArray(size).also { f.duplicate().apply { rewind() }.get(it) }
    fun toIntArray() = IntArray(size).also { i.duplicate().apply { rewind() }.get(it) }
    fun toLongArray() = LongArray(size).also { l.duplicate().apply { rewind() }.get(it) }
    /** The first [n] elements as a new big array (this one is left as it is). */
    fun prefix(n: Int): Big = Big(type, n, NativeMem.bytes(n.toLong() * (if (type == L) 8 else 4))).also { o ->
        val src = bytes.duplicate().order(bytes.order()); src.position(0); src.limit((n.toLong() * (if (type == L) 8 else 4)).toInt()); o.bytes.put(src); o.bytes.clear() }

    companion object {
        const val F = 0; const val I = 1; const val L = 2
        private fun make(type: Int, n: Int, w: Int): Big { require(n >= 0); return Big(type, n, NativeMem.bytes(n.toLong() * w)) }
        fun floats(n: Int) = make(F, n, 4)
        fun ints(n: Int) = make(I, n, 4)
        fun longs(n: Int) = make(L, n, 8)
        /** Takes ownership of a buffer [NativeMem] (or the ORT runner) malloc'd: [n] floats. */
        internal fun adopt(b: ByteBuffer, n: Int): Big { NativeMem.adopted(n.toLong() * 4); return Big(F, n, b.order(ByteOrder.nativeOrder())) }
        fun of(a: FloatArray) = floats(a.size).also { it.f.put(0, a) }
        fun of(a: IntArray) = ints(a.size).also { it.i.put(0, a) }
        fun of(a: LongArray) = longs(a.size).also { it.l.put(0, a) }
    }
}

/** Absolute bulk put (FloatBuffer.put(index, array) is API 35+; this works everywhere). */
private fun FloatBuffer.put(at: Int, a: FloatArray) { val d = duplicate(); d.position(at); d.put(a) }
private fun IntBuffer.put(at: Int, a: IntArray) { val d = duplicate(); d.position(at); d.put(a) }
private fun LongBuffer.put(at: Int, a: LongArray) { val d = duplicate(); d.position(at); d.put(a) }

/** Open-addressing hash map from a long key (any but Long.MIN_VALUE) to an int, in [Big] arrays (no boxed Longs on the heap). */
class BigLongIntMap(expected: Int) : AutoCloseable {
    private val cap: Int = Integer.highestOneBit(maxOf(16, expected * 2 - 1)) shl 1
    private val mask = cap - 1
    private val keys = Big.longs(cap).also { k -> val l = k.l; for (j in 0 until cap) l.put(j, EMPTY) }
    private val vals = Big.ints(cap)
    var size = 0; private set
    private fun slot(k: Long): Int { var h = (k * -7046029254386353131L) ushr 32; var s = (h.toInt()) and mask
        val kl = keys.l; while (true) { val v = kl.get(s); if (v == k || v == EMPTY) return s; s = (s + 1) and mask } }
    fun get(k: Long): Int { val s = slot(k); return if (keys.l.get(s) == k) vals.i.get(s) else -1 }
    /** The value for [k], or [v] stored under it if there was none. */
    fun getOrPut(k: Long, v: Int): Int { val s = slot(k); if (keys.l.get(s) == k) return vals.i.get(s)
        check(size < cap / 2 + cap / 4) { "hash map full" }; keys.l.put(s, k); vals.i.put(s, v); size++; return v }
    /** true when [k] was new. */
    fun add(k: Long): Boolean { val s = slot(k); if (keys.l.get(s) == k) return false; check(size < cap / 2 + cap / 4) { "hash set full" }; keys.l.put(s, k); size++; return true }
    override fun close() { keys.close(); vals.close() }
    private companion object { const val EMPTY = Long.MIN_VALUE }
}
