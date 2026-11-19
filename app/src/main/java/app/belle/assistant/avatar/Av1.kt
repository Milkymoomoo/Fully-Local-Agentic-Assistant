package app.belle.assistant.avatar

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Bundled AV1 decoding (dav1d, 8-bit, ~0.6 MB per ABI) for phones that have no AV1 decoder of their own
 * (common on Android 10/11). Used for AVIF backgrounds (still + animated) and AV1 video in WebM / MP4.
 */
object Av1 {
    private val loaded by lazy { runCatching { System.loadLibrary("belle_av1"); true }.getOrDefault(false) }
    val available: Boolean get() = loaded
    fun version(): String = if (loaded) nVersion() else "unavailable"

    @JvmStatic private external fun nOpen(): Long
    @JvmStatic private external fun nClose(h: Long)
    @JvmStatic private external fun nDecode(h: Long, data: ByteArray?, maxPx: Int): Array<IntArray>?
    @JvmStatic private external fun nVersion(): String

    private fun bmp(a: IntArray): Bitmap = Bitmap.createBitmap(a, 2, a[0], a[0], a[1], Bitmap.Config.ARGB_8888)

    /** Decoder session: feed temporal units in order, get pictures out. */
    class Session(private val maxPx: Int) : AutoCloseable {
        private val h = if (loaded) nOpen() else 0L
        init { check(h != 0L) { "AV1 decoder unavailable" } }
        fun feed(unit: ByteArray?): List<Bitmap> = nDecode(h, unit, maxPx)?.map(::bmp) ?: emptyList()
        override fun close() = nClose(h)
    }

    /** One AV1 still (an AVIF item): [configObus] = av1C's configOBUs (sequence header), prepended when present. */
    fun decodeStill(sample: ByteArray, av1C: ByteArray?, maxPx: Int): Bitmap? {
        if (!loaded) return null
        val cfg = av1C?.takeIf { it.size > 4 }?.copyOfRange(4, av1C.size) ?: ByteArray(0)
        Session(maxPx).use { s -> return (s.feed(cfg + sample) + s.feed(null)).firstOrNull() }
    }

    /**
     * AV1 track of a container Android can demux (WebM/Matroska, MP4, animated AVIF = ISO-BMFF): frames sampled to
     * ~[fps] (at most [maxFrames]) over the first [maxMs]. Returns the frames and their per-frame delays.
     */
    fun decodeTrack(f: File, maxPx: Int, maxFrames: Int, fps: Int = 8, maxMs: Long = 60_000): Pair<List<Bitmap>, Int>? {
        if (!loaded) return null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AV1 } ?: return null
            ex.selectTrack(t)
            val fmt = ex.getTrackFormat(t)
            val csd = if (fmt.containsKey("csd-0")) fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } else null
            val step = 1000L / fps
            val frames = ArrayList<Bitmap>(); var nextMs = 0L; var first = true
            val buf = java.nio.ByteBuffer.allocate(8 shl 20)
            Session(maxPx).use { s ->
                while (frames.size < maxFrames) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    val tMs = ex.sampleTime / 1000; if (tMs > maxMs) break
                    var unit = ByteArray(n).also { buf.get(it, 0, n) }
                    if (first && csd != null && csd.size > 4 && (csd[0].toInt() and 0x80) != 0) unit = csd.copyOfRange(4, csd.size) + unit
                    first = false
                    for (b in s.feed(unit)) if (tMs >= nextMs && frames.size < maxFrames) { frames += b; nextMs += step }
                    ex.advance()
                }
                for (b in s.feed(null)) if (frames.size < maxFrames && frames.isEmpty()) frames += b
            }
            return if (frames.isEmpty()) null else frames to step.toInt()
        } catch (e: Exception) { return null } finally { ex.release() }
    }

    /** Animated AVIF through our own ISO-BMFF track parser (Android 10/11's MediaExtractor doesn't read "avis"). */
    fun decodeAvifSequence(data: ByteArray, maxPx: Int, maxFrames: Int, fps: Int = 8): Pair<List<Bitmap>, Int>? {
        if (!loaded) return null
        val seq = Avif.sequence(data) ?: return null
        val step = 1000 / fps; val frames = ArrayList<Bitmap>(); var t = 0L; var next = 0L
        val cfg = seq.av1C?.takeIf { it.size > 4 }?.copyOfRange(4, seq.av1C.size)
        Session(maxPx).use { s ->
            for ((i, smp) in seq.samples.withIndex()) {
                if (frames.size >= maxFrames || t > 60_000) break
                val unit = if (i == 0 && cfg != null) cfg + smp else smp
                for (b in s.feed(unit)) if (t >= next && frames.size < maxFrames) { frames += b; next += step }
                t += seq.durMs[i]
            }
            if (frames.isEmpty()) frames += s.feed(null).take(1)
        }
        return if (frames.isEmpty()) null else frames to step
    }
}
