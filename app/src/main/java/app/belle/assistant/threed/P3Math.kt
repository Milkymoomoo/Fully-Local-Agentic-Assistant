package app.belle.assistant.threed

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure-Kotlin parts of the Pixal3D conditioning port (no Android types, unit-tested on the JVM against fixtures made
 * with ComfyUI's own code): PIL-compatible Lanczos resize, ComfyUI's crop-to-mask, the DINOv3 and NAF RoPE tables,
 * the voxel→image projection, grid_sample (bilinear, border, align_corners=false) and NAF's windowed attention
 * evaluated only at the pixels the projection touches (the full 1024×1024×1024 map would be 4 GB).
 */
object P3Math {
    /** RGB image, planar float [3][h*w] in 0..1. */
    class Img(val w: Int, val h: Int, val px: Array<FloatArray>)

    // ---------- PIL Image.resize(LANCZOS) on 8-bit RGB (two separable passes, each rounded/clipped to 8 bits) ----------
    private fun lanczos(x: Double): Double { val a = abs(x); if (a >= 3.0) return 0.0; if (a < 1e-12) return 1.0
        val p = PI * x; return 3.0 * sin(p) * sin(p / 3.0) / (p * p) }

    private class Coeffs(val start: IntArray, val n: IntArray, val k: Array<DoubleArray>)

    private fun triangle(x: Double): Double { val a = abs(x); return if (a < 1.0) 1.0 - a else 0.0 }

    private fun coeffs(inSize: Int, outSize: Int, radius: Double = 3.0, kernel: (Double) -> Double = ::lanczos): Coeffs {
        val scale = inSize.toDouble() / outSize; val fs = max(scale, 1.0); val support = radius * fs
        val start = IntArray(outSize); val n = IntArray(outSize); val k = arrayOfNulls<DoubleArray>(outSize)
        for (i in 0 until outSize) {
            val center = (i + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0); val xmax = min((center + support + 0.5).toInt(), inSize)
            val w = DoubleArray(xmax - xmin); var s = 0.0
            for (x in xmin until xmax) { val v = kernel((x - center + 0.5) / fs); w[x - xmin] = v; s += v }
            if (s != 0.0) for (j in w.indices) w[j] /= s
            start[i] = xmin; n[i] = xmax - xmin; k[i] = w
        }
        @Suppress("UNCHECKED_CAST") return Coeffs(start, n, k as Array<DoubleArray>)
    }

    // PIL keeps 8-bit precision between passes and uses fixed-point coefficients (22 bits); rounding here matches it
    // to within one level, which is the tolerance the tests use.
    private fun clip8(v: Double): Int { val r = floor(v + 0.5).toInt(); return if (r < 0) 0 else if (r > 255) 255 else r }

    /** 8-bit planes [3][w*h] → resized 8-bit planes [3][ow*oh]; same size returns a copy, as PIL does. */
    fun resizeLanczos8(src: Array<IntArray>, w: Int, h: Int, ow: Int, oh: Int): Array<IntArray> {
        if (ow == w && oh == h) return Array(src.size) { src[it].copyOf() }
        // PIL does horizontal first only when it needs both passes; it always resamples horizontally then vertically.
        val horiz = if (ow != w) { val c = coeffs(w, ow); Array(src.size) { ch -> val s = src[ch]; IntArray(ow * h).also { o ->
            for (y in 0 until h) for (x in 0 until ow) { var acc = 0.0; val st = c.start[x]; val k = c.k[x]
                for (j in 0 until c.n[x]) acc += s[y * w + st + j] * k[j]; o[y * ow + x] = clip8(acc) } } } } else src
        if (oh == h) return horiz
        val c = coeffs(h, oh)
        return Array(horiz.size) { ch -> val s = horiz[ch]; IntArray(ow * oh).also { o ->
            for (y in 0 until oh) { val st = c.start[y]; val k = c.k[y]
                for (x in 0 until ow) { var acc = 0.0; for (j in 0 until c.n[y]) acc += s[(st + j) * ow + x] * k[j]; o[y * ow + x] = clip8(acc) } } } }
    }

    fun to8(p: FloatArray) = IntArray(p.size) { val v = (p[it].coerceIn(0f, 1f) * 255f).toInt(); v }   // np.clip(255*x).astype(uint8) truncates
    fun from8(p: IntArray) = FloatArray(p.size) { p[it] / 255f }

    /** comfy.utils.common_upscale(..., "lanczos"): float image → uint8 → PIL Lanczos → float. */
    fun lanczos(img: Img, ow: Int, oh: Int): Img {
        val r = resizeLanczos8(Array(3) { to8(img.px[it]) }, img.w, img.h, ow, oh)
        return Img(ow, oh, Array(3) { from8(r[it]) })
    }

    /**
     * ComfyUI ImageCropToMask (pad_factor, centred square, black background, mask threshold 0.05, bbox from alpha>204,
     * inverted-mask detection), then Lanczos to [size]. [mask] is 0..1 per pixel at the image size.
     * Images larger than [size] are first Lanczos-shrunk, like the node does with max_image_size.
     */
    fun cropToMask(src: Img, maskIn: FloatArray, size: Int, padFactor: Double = 1.1): Img {
        var img = src; var mask = maskIn.copyOf(); var W = src.w; var H = src.h
        run { // inverted mask detection: border mostly fg and centre mostly bg
            var b = 0.0; var nb = 0
            for (x in 0 until W) { b += mask[x] + mask[(H - 1) * W + x]; nb += 2 }
            for (y in 0 until H) { b += mask[y * W] + mask[y * W + W - 1]; nb += 2 }
            var c = 0.0; var nc = 0
            for (y in H / 4 until H - H / 4) for (x in W / 4 until W - W / 4) { c += mask[y * W + x]; nc++ }
            if (b / nb > 0.5 && nc > 0 && c / nc < 0.5) for (i in mask.indices) mask[i] = 1f - mask[i]
        }
        for (i in mask.indices) if (mask[i] < 0.05f) mask[i] = 0f
        if (max(H, W) > size) {
            val s = size.toDouble() / max(H, W); val nw = (W * s).toInt(); val nh = (H * s).toInt()
            img = lanczos(img, nw, nh)
            mask = from8(resizeLanczos8(arrayOf(to8(mask)), W, H, nw, nh)[0]); W = nw; H = nh
        }
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        fun scan(inv: Boolean) { for (y in 0 until H) for (x in 0 until W) {
            val a = (mask[y * W + x].coerceIn(0f, 1f) * 255f).toInt(); if ((if (inv) 255 - a else a) > 204) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) } } }
        scan(false)
        if (x1 < 0) { scan(true); if (x1 >= 0) for (i in mask.indices) mask[i] = 1f - mask[i] }
        val cx1: Int; val cy1: Int; val cx2: Int; val cy2: Int
        if (x1 >= 0) {
            val cy = (y0 + y1) / 2.0; val cx = (x0 + x1) / 2.0; val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val cw: Int; val chh: Int
            if (bw.toDouble() / max(bh, 1) >= 1.0) { cw = (bw * padFactor).toInt(); chh = (bw * padFactor).toInt() } else { chh = (bh * padFactor).toInt(); cw = (bh * padFactor).toInt() }
            val hw = ceil(cw / 2.0).toInt(); val hh = ceil(chh / 2.0).toInt()
            cx1 = (cx - hw).toInt(); cy1 = (cy - hh).toInt(); cx2 = cx1 + 2 * hw; cy2 = cy1 + 2 * hh
        } else { cx1 = 0; cy1 = 0; cx2 = W; cy2 = H }
        val ow = cx2 - cx1; val oh = cy2 - cy1
        val out = Array(3) { FloatArray(ow * oh) }
        for (y in 0 until oh) for (x in 0 until ow) { val sx = cx1 + x; val sy = cy1 + y
            if (sx < 0 || sy < 0 || sx >= W || sy >= H) continue   // zero padding, black background
            val m = mask[sy * W + sx].coerceIn(0f, 1f)
            for (c in 0 until 3) out[c][y * ow + x] = (img.px[c][sy * W + sx] * m).coerceIn(0f, 1f) }
        return lanczos(Img(ow, oh, out), size, size)
    }

    /** F.interpolate(bilinear, antialias=True, align_corners=False) on float planes (PyTorch's PIL-style separable filter). */
    fun resizeAA(img: Img, ow: Int, oh: Int): Img {
        val cx = coeffs(img.w, ow, 1.0, ::triangle); val cy = coeffs(img.h, oh, 1.0, ::triangle)
        return Img(ow, oh, Array(3) { ch -> val s = img.px[ch]; val t = FloatArray(ow * img.h)
            for (y in 0 until img.h) for (x in 0 until ow) { var a = 0.0; for (j in 0 until cx.n[x]) a += s[y * img.w + cx.start[x] + j] * cx.k[x][j]; t[y * ow + x] = a.toFloat() }
            FloatArray(ow * oh).also { o -> for (y in 0 until oh) for (x in 0 until ow) { var a = 0.0; for (j in 0 until cy.n[y]) a += t[(cy.start[y] + j) * ow + x] * cy.k[y][j]; o[y * ow + x] = a.toFloat() } } })
    }

    /**
     * MoGe-2 horizontal FOV (degrees) from the raw points [3,h,w] and mask logits [1,h,w] head outputs, as ComfyUI does
     * it: bilinear to the crop size, nearest 64×64, exp depth remap, mask>0.5, 1-D least squares for the z shift with
     * the closed-form focal, fov = 2·atan(0.5 / (focal/2·√2)). Square crops only.
     */
    fun mogeFov(points: FloatArray, maskLogit: FloatArray, h: Int, w: Int, size: Int = 1024, n: Int = 64): Double {
        val us = ArrayList<Double>(); val vs = ArrayList<Double>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val zs = ArrayList<Double>()
        val span = 1 / sqrt(2.0)
        fun bil(m: FloatArray, c: Int, py: Int, px: Int): Double {
            val sy = max((py + 0.5) * h / size - 0.5, 0.0); val sx = max((px + 0.5) * w / size - 0.5, 0.0)
            val y0 = min(floor(sy).toInt(), h - 1); val x0 = min(floor(sx).toInt(), w - 1); val y1 = min(y0 + 1, h - 1); val x1 = min(x0 + 1, w - 1)
            val fy = sy - y0; val fx = sx - x0; val b = c * h * w
            return m[b + y0 * w + x0] * (1 - fx) * (1 - fy) + m[b + y0 * w + x1] * fx * (1 - fy) + m[b + y1 * w + x0] * (1 - fx) * fy + m[b + y1 * w + x1] * fx * fy
        }
        for (i in 0 until n) for (j in 0 until n) {
            val py = i * size / n; val px = j * size / n
            if (1 / (1 + exp(-bil(maskLogit, 0, py, px))) <= 0.5) continue
            val z = exp(bil(points, 2, py, px)); xs += bil(points, 0, py, px) * z; ys += bil(points, 1, py, px) * z; zs += z
            us += -span * (size - 1) / size + 2 * span * (size - 1) / size * px / (size - 1)
            vs += -span * (size - 1) / size + 2 * span * (size - 1) / size * py / (size - 1)
        }
        if (zs.size < 2) return 49.13
        fun focal(s: Double): Double { var a = 0.0; var b = 0.0; for (k in zs.indices) { val px = xs[k] / (zs[k] + s); val py = ys[k] / (zs[k] + s); a += px * us[k] + py * vs[k]; b += px * px + py * py }; return a / b }
        fun cost(s: Double): Double { val f = focal(s); var c = 0.0; for (k in zs.indices) { val rx = f * xs[k] / (zs[k] + s) - us[k]; val ry = f * ys[k] / (zs[k] + s) - vs[k]; c += rx * rx + ry * ry }; return c }
        val lo = -zs.min() + 1e-3; val hi = max(10.0, 10 * zs.maxOf { abs(it) })
        var best = lo; var bc = Double.MAX_VALUE; val step = (hi - lo) / 399
        for (k in 0 until 400) { val x = lo + k * step; val c = cost(x); if (c < bc) { bc = c; best = x } }
        var a = max(lo, best - step); var b = min(hi, best + step); val g = (sqrt(5.0) - 1) / 2
        repeat(100) { val x1 = b - g * (b - a); val x2 = a + g * (b - a); if (cost(x1) < cost(x2)) b = x2 else a = x1 }
        val f = focal((a + b) / 2); if (!f.isFinite() || f <= 0) return 60.0
        return Math.toDegrees(2 * kotlin.math.atan(0.5 / (f / 2 * sqrt(2.0))))
    }

    /** CHW float with per-channel normalisation, as ONNX input [1,3,h,w]. */
    fun chw(img: Img, mean: FloatArray? = null, std: FloatArray? = null): FloatArray {
        val n = img.w * img.h; val o = FloatArray(3 * n)
        for (c in 0 until 3) { val m = mean?.get(c) ?: 0f; val s = std?.get(c) ?: 1f; val p = img.px[c]; for (i in 0 until n) o[c * n + i] = (p[i] - m) / s }
        return o
    }

    // ---------- RoPE tables ----------
    private fun ropeTable(n: Int, freq: (Int) -> Double, nf: Int): Pair<FloatArray, FloatArray> {
        val d = 4 * nf; val cosT = FloatArray(n * n * d); val sinT = FloatArray(n * n * d)
        for (i in 0 until n) for (j in 0 until n) {
            val ch = (((i + 0.5f) / n) * 2f - 1f).toDouble(); val cw = (((j + 0.5f) / n) * 2f - 1f).toDouble(); val row = (i * n + j) * d
            for (a in 0 until 2) for (f in 0 until nf) { val ang = (2 * PI * (if (a == 0) ch else cw) * freq(f)).toFloat()
                val col = a * nf + f
                val cv = cos(ang.toDouble()).toFloat(); val sv = sin(ang.toDouble()).toFloat()
                cosT[row + col] = cv; sinT[row + col] = sv; cosT[row + col + 2 * nf] = cv; sinT[row + col + 2 * nf] = sv }
        }
        return cosT to sinT
    }
    /** DINOv3: inv_freq = 1/100^(4i/64), i<16, per patch centre; [n², 64]. */
    fun dinoRope(size: Int) = ropeTable(size / 16, { f -> 1.0 / 100.0.pow(f * 4.0 / 64.0) }, 16)
    /** NAF: periods = 100^(i/16) from the checkpoint, angle = 2π·coord/period; [H², 64]. */
    fun nafRope(h: Int) = ropeTable(h, { f -> 1.0 / 100.0.pow(f / 16.0) }, 16)

    // ---------- projection ----------
    /** Voxel coords [n*3] at grid [res] → grid_sample coords [n*2] for an R-pixel square image (front camera, FOV x). */
    fun uvNdc(coords: IntArray, res: Int, fovDeg: Double, imageRes: Int, meshScale: Float = 1f): FloatArray {
        val a = Math.toRadians(fovDeg); val d = (0.5 / tan(a / 2)).toFloat()
        val f = (16.0 / tan(a / 2) * imageRes / 32.0).toFloat(); val n = coords.size / 3; val out = FloatArray(n * 2)
        for (i in 0 until n) {
            fun nrm(v: Int) = if (res > 1) (v.toFloat() / (res - 1) * 2f - 1f) / meshScale / 2f else 0f
            val x = nrm(coords[i * 3]); val y = nrm(coords[i * 3 + 1]); val z = nrm(coords[i * 3 + 2])
            val den = -(z - d) + 1e-8f
            val xp = f * x / den + imageRes / 2f; val yp = -f * y / den + imageRes / 2f
            out[i * 2] = (xp + 0.5f) / imageRes * 2f - 1f; out[i * 2 + 1] = (yp + 0.5f) / imageRes * 2f - 1f
        }
        return out
    }
    fun denseCoords(res: Int) = IntArray(res * res * res * 3).also { c -> var k = 0
        for (x in 0 until res) for (y in 0 until res) for (z in 0 until res) { c[k++] = x; c[k++] = y; c[k++] = z } }

    /** grid_sample taps: for point i, 4 (pixel index y*W+x, weight). */
    class Taps(val pix: IntArray, val w: FloatArray)
    fun taps(uv: FloatArray, h: Int, w: Int): Taps {
        val n = uv.size / 2; val pix = IntArray(n * 4); val wt = FloatArray(n * 4)
        for (i in 0 until n) {
            val ix = (((uv[i * 2] + 1f) * w - 1f) / 2f).coerceIn(0f, (w - 1).toFloat()); val iy = (((uv[i * 2 + 1] + 1f) * h - 1f) / 2f).coerceIn(0f, (h - 1).toFloat())
            val x0 = floor(ix).toInt(); val y0 = floor(iy).toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = ix - x0; val fy = iy - y0
            pix[i * 4] = y0 * w + x0; wt[i * 4] = (1 - fx) * (1 - fy)
            pix[i * 4 + 1] = y0 * w + x1; wt[i * 4 + 1] = fx * (1 - fy)
            pix[i * 4 + 2] = y1 * w + x0; wt[i * 4 + 2] = (1 - fx) * fy
            pix[i * 4 + 3] = y1 * w + x1; wt[i * 4 + 3] = fx * fy
        }
        return Taps(pix, wt)
    }

    /** Bilinear sample of a [C, h, w] map at [uv] into out[n, stride] starting at column [off]. */
    fun sampleMap(fm: FloatArray, c: Int, h: Int, w: Int, uv: FloatArray, out: FloatArray, stride: Int, off: Int) {
        val t = taps(uv, h, w); val n = uv.size / 2; val hw = h * w
        for (i in 0 until n) for (k in 0 until 4) { val p = t.pix[i * 4 + k]; val wt = t.w[i * 4 + k]; if (wt == 0f) continue
            val o = i * stride + off; for (ch in 0 until c) out[o + ch] += fm[ch * hw + p] * wt }
    }

    /** Block average of a [c, h, w] map by [f]. */
    fun avgPool(q: FloatArray, c: Int, h: Int, w: Int, f: Int): FloatArray = avgPool(java.nio.FloatBuffer.wrap(q), c, h, w, f)
    /** [q] may be native memory (the 256 × 512² NAF map is 268 MB: too big for the Java heap). */
    fun avgPool(q: java.nio.FloatBuffer, c: Int, h: Int, w: Int, f: Int): FloatArray {
        val oh = h / f; val ow = w / f; val o = FloatArray(c * oh * ow); val inv = 1f / (f * f)
        for (ch in 0 until c) for (y in 0 until h) for (x in 0 until w) o[ch * oh * ow + (y / f) * ow + x / f] += q.get(ch * h * w + y * w + x)
        for (i in o.indices) o[i] *= inv
        return o
    }

    /**
     * NAF high-res features at the given pixels: per head, softmax(q·k/√d) over the 9×9 zero-padded LR window of the
     * pixel's LR cell, times v. q [256,H,W], k [256,h,w], v [C,h,w] → [P, C].
     */
    fun nafPixels(q: FloatArray, H: Int, W: Int, k: FloatArray, v: FloatArray, C: Int, h: Int, w: Int, pixels: IntArray, ksz: Int = 9, heads: Int = 4): FloatArray =
        nafPixels(java.nio.FloatBuffer.wrap(q), H, W, k, v, C, h, w, pixels, ksz, heads)
    fun nafPixels(q: java.nio.FloatBuffer, H: Int, W: Int, k: FloatArray, v: FloatArray, C: Int, h: Int, w: Int, pixels: IntArray, ksz: Int = 9, heads: Int = 4): FloatArray {
        val cq = 256; val dq = cq / heads; val dv = C / heads; val dh = H / h; val dw = W / w; val p = ksz / 2
        val scale = 1f / sqrt(dq.toFloat()); val out = FloatArray(pixels.size * C); val s = FloatArray(ksz * ksz); val qq = FloatArray(cq)
        val HW = H * W; val hw = h * w
        for ((i, px) in pixels.withIndex()) {
            val yy = px / W; val xx = px % W; val r = yy / dh; val c = xx / dw
            for (ch in 0 until cq) qq[ch] = q.get(ch * HW + px)
            for (hd in 0 until heads) {
                var mx = Float.NEGATIVE_INFINITY
                for (a in 0 until ksz) for (b in 0 until ksz) { val ly = r + a - p; val lx = c + b - p; var dot = 0f
                    if (ly in 0 until h && lx in 0 until w) { val base = ly * w + lx; for (d in 0 until dq) dot += qq[hd * dq + d] * k[(hd * dq + d) * hw + base] }
                    val sc = dot * scale; s[a * ksz + b] = sc; if (sc > mx) mx = sc }
                var sum = 0f; for (j in s.indices) { s[j] = exp(s[j] - mx); sum += s[j] }
                val o = i * C + hd * dv
                for (a in 0 until ksz) for (b in 0 until ksz) { val ly = r + a - p; val lx = c + b - p
                    if (ly !in 0 until h || lx !in 0 until w) continue
                    val wgt = s[a * ksz + b] / sum; val base = ly * w + lx
                    for (d in 0 until dv) out[o + d] += wgt * v[(hd * dv + d) * hw + base] }
            }
        }
        return out
    }

    /** grid_sample of the (never materialised) NAF map at [uv] into out[n, stride] at column [off]. Returns pixels evaluated. */
    fun sampleNaf(q: FloatArray, H: Int, W: Int, k: FloatArray, v: FloatArray, C: Int, h: Int, w: Int, uv: FloatArray, out: FloatArray, stride: Int, off: Int): Int =
        sampleNaf(java.nio.FloatBuffer.wrap(q), H, W, k, v, C, h, w, uv, out, stride, off)
    fun sampleNaf(q: java.nio.FloatBuffer, H: Int, W: Int, k: FloatArray, v: FloatArray, C: Int, h: Int, w: Int, uv: FloatArray, out: FloatArray, stride: Int, off: Int): Int {
        val t = taps(uv, H, W); val uniq = t.pix.distinct().sorted().toIntArray()
        val idx = HashMap<Int, Int>(uniq.size * 2); uniq.forEachIndexed { j, p -> idx[p] = j }
        val f = nafPixels(q, H, W, k, v, C, h, w, uniq)
        val n = uv.size / 2
        for (i in 0 until n) for (kk in 0 until 4) { val wt = t.w[i * 4 + kk]; if (wt == 0f) continue
            val j = idx[t.pix[i * 4 + kk]]!!; val o = i * stride + off; for (ch in 0 until C) out[o + ch] += f[j * C + ch] * wt }
        return uniq.size
    }
}
