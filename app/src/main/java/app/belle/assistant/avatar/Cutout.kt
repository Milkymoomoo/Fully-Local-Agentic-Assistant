package app.belle.assistant.avatar

import android.graphics.Bitmap
import java.io.File

/**
 * Removes a picture's own background so a chosen background can show behind the avatar.
 *  1. Pictures that already have transparency (PNG/WebP/AVIF with alpha, our 3D renders) are used as they are.
 *  2. Otherwise a small on-device matting model (U²-Net-P, 4.6 MB, Apache-2.0, bundled; or ISNet if the user
 *     downloaded it) separates the character from any background, busy ones included (lamps, windows, rooms).
 *  3. If the model can't run, the background is grown in from the picture's edges: pixels connected to the border whose colour
 *     is close to one of the border's dominant colours and changes smoothly from its neighbour (flat colours,
 *     gradients, soft studio backdrops). The result gets a 1–2 px feathered edge.
 *  If that would remove almost everything or almost nothing, the picture is left untouched (a busy photo
 *  background can't be cut this way; the chosen background then still shows around the picture's frame).
 * Results are cached in memory and on disk per source file + size, so it runs once per picture.
 */
object Cutout {
    private val mem = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }
    @Volatile var cacheDir: File? = null
    /** Matting model files (set by [init]); the better ISNet is used when it was downloaded. */
    @Volatile var models: List<File> = emptyList()
    @Volatile var lastModel = ""

    @Volatile private var appCtx: android.content.Context? = null
    /** Points the cutout at its caches (cheap; the model file is copied out of the APK later, off the main thread). */
    fun init(ctx: android.content.Context) { if (appCtx == null) { appCtx = ctx.applicationContext; cacheDir = ctx.cacheDir } }

    @Synchronized private fun ensureModels(): List<File> {
        val ctx = appCtx ?: return models
        val dir = File(ctx.filesDir, "models/matting").apply { mkdirs() }
        val u2 = File(dir, "u2netp.onnx")
        if (u2.length() == 0L) runCatching { ctx.assets.open("models/u2netp.onnx").use { i -> File(dir, "u2netp.tmp").also { f -> f.outputStream().use { i.copyTo(it) }; f.renameTo(u2) } } }
        models = listOf(File(dir, "isnet-general-use.onnx"), u2).filter { it.length() > 0 }
        return models
    }

    /** Foreground probability per pixel (0..1) at w×h from the matting model, or null if no model could run. */
    fun matte(src: Bitmap, size: Int = 0): FloatArray? {
        for (m in ensureModels()) {
            val s = if (size > 0) size else if (m.name.startsWith("isnet")) 1024 else 320
            val mean = if (m.name.startsWith("isnet")) floatArrayOf(0.5f, 0.5f, 0.5f) else floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = if (m.name.startsWith("isnet")) floatArrayOf(1f, 1f, 1f) else floatArrayOf(0.229f, 0.224f, 0.225f)
            val r = runCatching {
                val small = Bitmap.createScaledBitmap(if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false), s, s, true)
                val px = IntArray(s * s); small.getPixels(px, 0, s, 0, 0, s, s)
                val x = FloatArray(3 * s * s)
                for (i in 0 until s * s) { val c = px[i]
                    x[i] = ((c shr 16 and 255) / 255f - mean[0]) / std[0]
                    x[s * s + i] = ((c shr 8 and 255) / 255f - mean[1]) / std[1]
                    x[2 * s * s + i] = ((c and 255) / 255f - mean[2]) / std[2] }
                app.belle.assistant.threed.Ort.Session(m.path, 2).use { sess ->
                    val out = sess.run(mapOf(sess.inputs[0] to (x to longArrayOf(1, 3, s.toLong(), s.toLong()))))
                    out[sess.outputs[0]]!!.data
                }
            }.onFailure { android.util.Log.w("Cutout", "matting with ${m.name} failed", it) }.getOrNull() ?: continue
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (v in r) { if (v < lo) lo = v; if (v > hi) hi = v }
            val span = (hi - lo).coerceAtLeast(1e-6f)
            lastModel = m.name
            return FloatArray(r.size) { (r[it] - lo) / span }.let { upsample(it, s, src.width, src.height) }
        }
        return null
    }

    /** Bilinear upsample of an s×s mask to w×h. */
    private fun upsample(m: FloatArray, s: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * s / h - 0.5f).coerceIn(0f, s - 1f); val y0 = fy.toInt(); val y1 = minOf(y0 + 1, s - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * s / w - 0.5f).coerceIn(0f, s - 1f); val x0 = fx.toInt(); val x1 = minOf(x0 + 1, s - 1); val tx = fx - x0
                val a = m[y0 * s + x0] * (1 - tx) + m[y0 * s + x1] * tx
                val b = m[y1 * s + x0] * (1 - tx) + m[y1 * s + x1] * tx
                out[y * w + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }
    /** What the last [cut] did: "alpha" (already transparent), "cut" (background removed) or "kept" (couldn't separate). */
    @Volatile var lastResult = ""

    /** [key] identifies the source (e.g. path + mtime); null = no caching. */
    fun of(src: Bitmap, key: String?): Bitmap {
        val k = key?.let { "$it|${src.width}x${src.height}|v2" }
        if (k != null) synchronized(mem) { mem[k]?.let { return it } }
        val disk = k?.let { kk -> cacheDir?.let { File(File(it, "cutout").apply { mkdirs() }, kk.hashCode().toUInt().toString(16) + ".png") } }
        disk?.takeIf { it.length() > 0 }?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.takeIf { it.width == src.width && it.height == src.height }?.let { b ->
            synchronized(mem) { mem[k] = b }; return b } }
        val t0 = System.currentTimeMillis()
        val out = runCatching { cut(src) }.onFailure { android.util.Log.w("Cutout", "cut failed", it) }.getOrDefault(src)
        android.util.Log.i("Cutout", "${src.width}x${src.height}: $lastResult in ${System.currentTimeMillis() - t0} ms (models=${models.map { it.name }})")
        if (k != null) {
            synchronized(mem) { mem[k] = out }
            if (out !== src) disk?.let { f -> runCatching { f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        }
        return out
    }

    /** True when at least a fifth of the border is already see-through. */
    fun hasAlpha(px: IntArray, w: Int, h: Int): Boolean {
        var n = 0; var t = 0
        fun at(i: Int) { n++; if ((px[i] ushr 24) < 200) t++ }
        for (x in 0 until w) { at(x); at((h - 1) * w + x) }
        for (y in 1 until h - 1) { at(y * w); at(y * w + w - 1) }
        return t * 5 >= n
    }

    fun cut(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w < 8 || h < 8) return src
        val px = IntArray(w * h)
        (if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)).getPixels(px, 0, w, 0, 0, w, h)
        if (hasAlpha(px, w, h)) { lastResult = "alpha"; return src }
        lastResult = "kept"
        matte(src)?.let { m ->
            val cover = m.count { it > 0.5f }.toFloat() / m.size
            if (cover in 0.03f..0.97f) {
                // crisp but anti-aliased edge: smoothstep over the model's uncertain band
                for (i in px.indices) {
                    val v = ((m[i] - 0.25f) / 0.5f).coerceIn(0f, 1f); val a = (v * v * (3 - 2 * v) * 255).toInt()
                    px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
                }
                lastResult = "matte:$lastModel"
                return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
        }
        // dominant border colours (5-bit-per-channel histogram)
        val hist = HashMap<Int, IntArray>()   // bin → [count, rSum, gSum, bSum]
        var borderN = 0
        fun addB(i: Int) { val c = px[i]; val bin = ((c shr 19) and 31 shl 10) or ((c shr 11) and 31 shl 5) or ((c shr 3) and 31)
            val a = hist.getOrPut(bin) { IntArray(4) }; a[0]++; a[1] += c shr 16 and 255; a[2] += c shr 8 and 255; a[3] += c and 255; borderN++ }
        for (x in 0 until w) { addB(x); addB((h - 1) * w + x) }
        for (y in 1 until h - 1) { addB(y * w); addB(y * w + w - 1) }
        // merge neighbouring bins by taking the top bins and their mean colours
        val top = hist.values.sortedByDescending { it[0] }.take(12).filter { it[0] * 50 >= borderN }   // ≥ 2 % of the border each
        if (top.isEmpty()) return src
        val cols = top.map { intArrayOf(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }
        fun far(c: Int): Int { val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            var best = 999; for (k in cols) { val d = maxOf(Math.abs(r - k[0]), Math.abs(g - k[1]), Math.abs(b - k[2])); if (d < best) best = d }; return best }
        fun step(a: Int, b: Int) = maxOf(Math.abs((a shr 16 and 255) - (b shr 16 and 255)), Math.abs((a shr 8 and 255) - (b shr 8 and 255)), Math.abs((a and 255) - (b and 255)))
        val SEED = 40; val STEP = 14; val FAR = 72
        val bg = BooleanArray(w * h); val q = IntArray(w * h); var qh = 0; var qt = 0
        fun seed(i: Int) { if (!bg[i] && far(px[i]) <= SEED) { bg[i] = true; q[qt++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 1 until h - 1) { seed(y * w); seed(y * w + w - 1) }
        while (qh < qt) {
            val i = q[qh++]; val x = i % w; val y = i / w; val c = px[i]
            fun tryN(j: Int) { if (!bg[j] && step(c, px[j]) <= STEP && far(px[j]) <= FAR) { bg[j] = true; q[qt++] = j } }
            if (x > 0) tryN(i - 1); if (x < w - 1) tryN(i + 1); if (y > 0) tryN(i - w); if (y < h - 1) tryN(i + w)
        }
        val frac = qt.toFloat() / (w * h)
        if (frac < 0.03f || frac > 0.90f) return src   // lastResult stays "kept"
        // feathered alpha: 3×3 box blur of the mask, twice (≈ 2 px soft edge), only where it matters
        var a = FloatArray(w * h) { if (bg[it]) 0f else 1f }
        repeat(2) {
            val o = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var n = 0
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= w) continue; s += a[yy * w + xx]; n++ } }
                o[y * w + x] = s / n
            }
            a = o
        }
        for (i in px.indices) {
            val al = if (bg[i]) (a[i] * 2f - 1f).coerceIn(0f, 1f) else a[i].coerceIn(0f, 1f) // keep the subject solid, fade only the background side
            val keep = if (bg[i]) al else maxOf(al, 0.85f).let { if (a[i] > 0.99f) 1f else it }
            px[i] = ((keep * ((px[i] ushr 24))).toInt().coerceIn(0, 255) shl 24) or (px[i] and 0xFFFFFF)
        }
        lastResult = "cut"
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** For the settings page (blocking; call off the main thread): how a picture will look over a custom background. */
    fun describe(src: Bitmap?, animatedGif: Boolean): String = when {
        animatedGif -> "This avatar is an animated GIF: it keeps its own background (set Avatar › GIF to \"still\" to cut the character out)."
        src == null -> ""
        else -> { cut(if (maxOf(src.width, src.height) > 512) Bitmap.createScaledBitmap(src, src.width * 512 / maxOf(src.width, src.height), src.height * 512 / maxOf(src.width, src.height), true) else src)
            when (lastResult) {
                "alpha" -> "The picture is already transparent around the character, so your background shows behind it."
                "cut" -> "The picture's own background is cut out automatically, so yours shows behind the character."
                else -> "I couldn't separate the character from this picture's busy background, so it keeps its own; your background shows only around the picture's frame. A PNG with a transparent background works best."
            } }
    }
}
