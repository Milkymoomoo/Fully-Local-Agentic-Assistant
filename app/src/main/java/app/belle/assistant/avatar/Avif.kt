package app.belle.assistant.avatar

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Still-AVIF decoder for Android 10–11 (API 29/30), where ImageDecoder can't read AVIF yet.
 * No bundled library: it reads the HEIF/ISOBMFF container itself (pitm, iloc, iinf, iprp/ipco/ipma) to find the
 * primary AV1 image item, then decodes that one AV1 frame with the platform's AV1 decoder (MediaCodec "video/av01",
 * the libgav1/dav1d software decoder every Android 10+ device ships). Grid images and the alpha plane are not
 * supported here (the picture comes out opaque); on Android 12+ ImageDecoder is used instead and handles both.
 */
object Avif {
    class Item(val id: Int, var type: String = "", var extents: MutableList<LongArray> = mutableListOf(), var method: Int = 0, val props: MutableList<Int> = mutableListOf())

    fun isAvif(b: ByteArray): Boolean = b.size > 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
        String(b, 8, minOf(b.size - 8, 32), Charsets.ISO_8859_1).let { it.contains("avif") || it.contains("avis") }

    fun decode(data: ByteArray, maxPx: Int = 1024): Bitmap? {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var meta: IntRange? = null
        boxes(bb, 0, data.size) { type, s, e -> if (type == "meta") meta = s until e }
        val m = meta ?: return null
        var primary = 1; val items = HashMap<Int, Item>(); val props = ArrayList<Pair<String, IntRange>>(); var idat: IntRange? = null
        boxes(bb, m.first + 4, m.last + 1) { type, s, e ->       // meta is a FullBox: skip version/flags
            when (type) {
                "pitm" -> { val v = data[s].toInt(); primary = if (v == 0) bb.getShort(s + 4).toInt() and 0xFFFF else bb.getInt(s + 4) }
                "iinf" -> { val v = data[s].toInt(); val off = s + 4 + (if (v == 0) 2 else 4)
                    boxes(bb, off, e) { t2, s2, _ -> if (t2 == "infe") { val v2 = data[s2].toInt(); if (v2 >= 2) {
                        val id = if (v2 == 2) bb.getShort(s2 + 4).toInt() and 0xFFFF else bb.getInt(s2 + 4)
                        val to = s2 + 4 + (if (v2 == 2) 2 else 4) + 2
                        items.getOrPut(id) { Item(id) }.type = String(data, to, 4, Charsets.ISO_8859_1) } } } }
                "iloc" -> parseIloc(bb, s, items)
                "idat" -> idat = s until e
                "iprp" -> boxes(bb, s, e) { t2, s2, e2 ->
                    if (t2 == "ipco") boxes(bb, s2, e2) { t3, s3, e3 -> props += t3 to (s3 until e3) }
                    if (t2 == "ipma") { val v = data[s2].toInt(); val flags = bb.getInt(s2) and 0xFFFFFF; var o = s2 + 4
                        val n = bb.getInt(o); o += 4
                        repeat(n) { val id = if (v < 1) { val x = bb.getShort(o).toInt() and 0xFFFF; o += 2; x } else { val x = bb.getInt(o); o += 4; x }
                            val cnt = data[o].toInt() and 0xFF; o++
                            repeat(cnt) { val idx = if (flags and 1 != 0) { val x = bb.getShort(o).toInt() and 0x7FFF; o += 2; x } else { val x = data[o].toInt() and 0x7F; o++; x }
                                if (idx > 0) items.getOrPut(id) { Item(id) }.props += idx } } }
                }
            }
        }
        val it = items[primary] ?: return null
        if (it.type != "av01") return null                     // grid / other item types: not supported here
        var w = 0; var h = 0; var av1C: ByteArray? = null
        for (pi in it.props) { val (t, r) = props.getOrNull(pi - 1) ?: continue
            when (t) { "ispe" -> { w = bb.getInt(r.first + 4); h = bb.getInt(r.first + 8) }; "av1C" -> av1C = data.copyOfRange(r.first, r.last + 1) } }
        if (w <= 0 || h <= 0) return null
        val payload = java.io.ByteArrayOutputStream()
        for (ex in it.extents) { val base = if (it.method == 1) (idat?.first ?: return null).toLong() else 0L
            payload.write(data, (base + ex[0]).toInt(), ex[1].toInt()) }
        val sample = payload.toByteArray()
        // the bundled dav1d first (always there), then the phone's own AV1 codec if it has one
        return runCatching { Av1.decodeStill(sample, av1C, maxPx) }.getOrNull() ?: decodeAv1(sample, av1C, w, h, maxPx)
    }

    /** Animated AVIF ("avis"): the AV1 track's samples (one temporal unit each), their durations in ms, and av1C. */
    class Sequence(val samples: List<ByteArray>, val durMs: IntArray, val av1C: ByteArray?)

    fun sequence(data: ByteArray, maxSamples: Int = 600): Sequence? = runCatching {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var result: Sequence? = null
        fun inside(type: String, s: Int, e: Int, f: (Int, Int) -> Unit) = boxes(bb, s, e) { t, a, b -> if (t == type) f(a, b) }
        boxes(bb, 0, data.size) { t, ms, me -> if (t == "moov" && result == null) boxes(bb, ms, me) { t2, ts, te ->
            if (t2 != "trak" || result != null) return@boxes
            var timescale = 1000L; var av1C: ByteArray? = null; var isAv1 = false
            var sizes = IntArray(0); var chunkOff = LongArray(0); val stsc = ArrayList<IntArray>(); val stts = ArrayList<IntArray>()
            inside("mdia", ts, te) { ds, de ->
                inside("mdhd", ds, de) { hs, _ -> val v = data[hs].toInt(); timescale = (if (v == 1) bb.getInt(hs + 4 + 16) else bb.getInt(hs + 4 + 8)).toLong() and 0xFFFFFFFFL }
                inside("minf", ds, de) { ns, ne -> inside("stbl", ns, ne) { ss, se ->
                    boxes(bb, ss, se) { bt, bs, be ->
                        when (bt) {
                            "stsd" -> { val n = bb.getInt(bs + 4); var o = bs + 8
                                repeat(n) { val sz = bb.getInt(o); val typ = String(data, o + 4, 4, Charsets.ISO_8859_1)
                                    if (typ == "av01") { isAv1 = true
                                        // VisualSampleEntry: 8 header + 6 reserved + 2 dref + 70 visual fields, then child boxes
                                        boxes(bb, o + 8 + 78, o + sz) { ct, cs, ce -> if (ct == "av1C") av1C = data.copyOfRange(cs, ce) } }
                                    o += sz } }
                            "stsz" -> { val fixed = bb.getInt(bs + 4); val n = bb.getInt(bs + 8)
                                sizes = IntArray(n) { if (fixed != 0) fixed else bb.getInt(bs + 12 + it * 4) } }
                            "stco" -> { val n = bb.getInt(bs + 4); chunkOff = LongArray(n) { bb.getInt(bs + 8 + it * 4).toLong() and 0xFFFFFFFFL } }
                            "co64" -> { val n = bb.getInt(bs + 4); chunkOff = LongArray(n) { bb.getLong(bs + 8 + it * 8) } }
                            "stsc" -> { val n = bb.getInt(bs + 4); repeat(n) { stsc += intArrayOf(bb.getInt(bs + 8 + it * 12), bb.getInt(bs + 12 + it * 12)) } }
                            "stts" -> { val n = bb.getInt(bs + 4); repeat(n) { stts += intArrayOf(bb.getInt(bs + 8 + it * 8), bb.getInt(bs + 12 + it * 8)) } }
                        }
                    } } }
            }
            if (!isAv1 || sizes.isEmpty() || chunkOff.isEmpty()) return@boxes
            // sample → file offset through the sample-to-chunk table
            val samples = ArrayList<ByteArray>(); var si = 0
            for (c in chunkOff.indices) {
                val per = stsc.lastOrNull { it[0] - 1 <= c }?.get(1) ?: 1
                var o = chunkOff[c]
                repeat(per) { if (si < sizes.size && samples.size < maxSamples) { samples += data.copyOfRange(o.toInt(), (o + sizes[si]).toInt()); o += sizes[si]; si++ } }
            }
            val durs = ArrayList<Int>(); for (e in stts) repeat(e[0]) { durs += (e[1] * 1000L / timescale).toInt().coerceAtLeast(1) }
            result = Sequence(samples, IntArray(samples.size) { durs.getOrElse(it) { 100 } }, av1C)
        } }
        result
    }.getOrNull()

    private fun parseIloc(bb: ByteBuffer, s: Int, items: HashMap<Int, Item>) {
        val v = bb.get(s).toInt(); var o = s + 4
        val a = bb.get(o).toInt() and 0xFF; val b = bb.get(o + 1).toInt() and 0xFF; o += 2
        val offSize = a shr 4; val lenSize = a and 15; val baseSize = b shr 4; val idxSize = if (v == 1 || v == 2) b and 15 else 0
        fun rd(n: Int): Long { val r = when (n) { 0 -> 0L; 4 -> bb.getInt(o).toLong() and 0xFFFFFFFFL; 8 -> bb.getLong(o); else -> error("iloc size $n") }; o += n; return r }
        val count = if (v < 2) { val x = bb.getShort(o).toInt() and 0xFFFF; o += 2; x } else { val x = bb.getInt(o); o += 4; x }
        repeat(count) {
            val id = if (v < 2) { val x = bb.getShort(o).toInt() and 0xFFFF; o += 2; x } else { val x = bb.getInt(o); o += 4; x }
            val method = if (v == 1 || v == 2) { val x = bb.getShort(o).toInt() and 15; o += 2; x } else 0
            o += 2 // data_reference_index
            val base = rd(baseSize)
            val n = bb.getShort(o).toInt() and 0xFFFF; o += 2
            val item = items.getOrPut(id) { Item(id) }; item.method = method
            repeat(n) { rd(idxSize); val off = rd(offSize); val len = rd(lenSize); item.extents += longArrayOf(base + off, len) }
        }
    }

    private fun boxes(bb: ByteBuffer, start: Int, end: Int, f: (String, Int, Int) -> Unit) {
        var o = start
        while (o + 8 <= end) {
            var size = bb.getInt(o).toLong() and 0xFFFFFFFFL; val type = String(ByteArray(4) { bb.get(o + 4 + it) }, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) { size = bb.getLong(o + 8); hdr = 16 } else if (size == 0L) size = (end - o).toLong()
            if (size < hdr || o + size > end) return
            f(type, o + hdr, (o + size).toInt()); o += size.toInt()
        }
    }

    /** One AV1 key frame → Bitmap via MediaCodec (YUV 4:2:0 output, BT.601 full/limited from the stream defaults). */
    fun decodeAv1(sample: ByteArray, av1C: ByteArray?, w: Int, h: Int, maxPx: Int): Bitmap? {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, w, h)
        av1C?.let { fmt.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AV1) }.getOrNull() ?: return null
        try {
            codec.configure(fmt, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo(); var sent = false; var eos = false; val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 15000) {
                if (!sent) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) { codec.getInputBuffer(i)!!.apply { clear(); put(sample) }
                    codec.queueInputBuffer(i, 0, sample.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME); sent = true } }
                else if (!eos) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true } }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val img = codec.getOutputImage(o)
                    val bmp = img?.let { yuvToBitmap(it, w, h, maxPx) }
                    img?.close(); codec.releaseOutputBuffer(o, false)
                    if (bmp != null) return bmp
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    fun yuvToBitmap(img: android.media.Image, w: Int, h: Int, maxPx: Int): Bitmap {
        val step = maxOf(1, (maxOf(w, h) + maxPx - 1) / maxPx)
        val ow = w / step; val oh = h / step
        val px = IntArray(ow * oh)
        val (yp, up, vp) = img.planes.let { Triple(it[0], it[1], it[2]) }
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        for (y in 0 until oh) { val sy = y * step
            for (x in 0 until ow) { val sx = x * step
                val Y = (yb.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 0xFF) - 16
                val ci = (sy / 2) * up.rowStride + (sx / 2) * up.pixelStride; val cv = (sy / 2) * vp.rowStride + (sx / 2) * vp.pixelStride
                val U = (ub.get(ci).toInt() and 0xFF) - 128; val V = (vb.get(cv).toInt() and 0xFF) - 128
                val r = (1.164f * Y + 1.596f * V).toInt().coerceIn(0, 255)
                val g = (1.164f * Y - 0.392f * U - 0.813f * V).toInt().coerceIn(0, 255)
                val bl = (1.164f * Y + 2.017f * U).toInt().coerceIn(0, 255)
                px[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl } }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }
}
