package app.belle.assistant.mesh

/**
 * Chart-aware mipmaps for texture atlases (every imported, generated or bundled model goes through this).
 *
 * Generated models (Pixal3D/TRELLIS/xatlas…) use atlases split into many small charts separated by empty, usually
 * black, gutters. A plain box-filter mip chain averages those gutters into the chart edges, so the lower mips the
 * detail slider and the retro shaders use show dark smears all over the model. Here:
 *  1. the texels the mesh actually uses are found by rasterising every triangle in UV space (1-texel margin);
 *  2. each mip averages ONLY used texels ("push");
 *  3. unused texels at every level are then filled from the next coarser level ("pull"), so gutters carry the
 *     colour of the chart next to them and bilinear/mip sampling never pulls in black.
 * Pure Kotlin on ARGB IntArrays so it runs in unit tests as well as on the phone.
 */
object TextureMips {
    class Level(val w: Int, val h: Int, val px: IntArray)

    /** Which texels of a [w]×[h] texture the triangles cover (UVs wrap like GL_REPEAT). */
    fun coverage(w: Int, h: Int, uv: FloatArray, idx: IntArray, tris: Int = idx.size / 3): BooleanArray {
        val m = BooleanArray(w * h)
        for (t in 0 until tris) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            var u0 = uv[a * 2]; var v0 = uv[a * 2 + 1]; var u1 = uv[b * 2]; var v1 = uv[b * 2 + 1]; var u2 = uv[c * 2]; var v2 = uv[c * 2 + 1]
            val fu = kotlin.math.floor(minOf(u0, u1, u2)); val fv = kotlin.math.floor(minOf(v0, v1, v2))
            u0 -= fu; u1 -= fu; u2 -= fu; v0 -= fv; v1 -= fv; v2 -= fv
            val x0 = u0 * w; val y0 = v0 * h; val x1 = u1 * w; val y1 = v1 * h; val x2 = u2 * w; val y2 = v2 * h
            val minX = kotlin.math.floor(minOf(x0, x1, x2)).toInt() - 1; val maxX = kotlin.math.ceil(maxOf(x0, x1, x2)).toInt() + 1
            val minY = kotlin.math.floor(minOf(y0, y1, y2)).toInt() - 1; val maxY = kotlin.math.ceil(maxOf(y0, y1, y2)).toInt() + 1
            if (maxX - minX > w + 2 || maxY - minY > h + 2) continue
            val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            // margin in barycentric units ≈ 1.5 texels
            for (py in minY..maxY) for (px in minX..maxX) {
                val fx = px + 0.5f; val fy = py + 0.5f
                val inside = if (kotlin.math.abs(den) < 1e-12f) true else {
                    val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den; val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den; val w2 = 1 - w0 - w1
                    // distance-ish test: accept texels within ~1.5 px of the triangle
                    val e = 1.5f / maxOf(1e-6f, kotlin.math.sqrt(kotlin.math.abs(den)))
                    w0 > -e && w1 > -e && w2 > -e
                }
                if (inside) { val xx = ((px % w) + w) % w; val yy = ((py % h) + h) % h; m[yy * w + xx] = true }
            }
        }
        return m
    }

    /** The full chain (level 0 included, gutters filled), down to 1×1. Level 0 stays as ints to save memory. */
    fun build(w: Int, h: Int, argb: IntArray, used: BooleanArray): List<Level> {
        if (used.none { it }) return build(w, h, argb, BooleanArray(w * h) { true })
        val ws = arrayListOf(w); val hs = arrayListOf(h)
        val ch = ArrayList<Array<FloatArray>>()   // per level ≥ 1: R, G, B, A, weight
        fun parent(l: Int, q: Int, k: Int): Float = if (l == 0) { if (!used[q]) 0f else { val c = argb[q]; when (k) { 0 -> ((c shr 16) and 255).toFloat(); 1 -> ((c shr 8) and 255).toFloat(); 2 -> (c and 255).toFloat(); 3 -> (c ushr 24).toFloat(); else -> 1f } } } else ch[l - 1][k][q]
        while (ws.last() > 1 || hs.last() > 1) {
            val l = ws.size - 1; val pw = ws.last(); val ph = hs.last(); val nw = maxOf(1, pw / 2); val nh = maxOf(1, ph / 2); val n = nw * nh
            val c = Array(5) { FloatArray(n) }
            for (y in 0 until nh) for (x in 0 until nw) { val o = y * nw + x; var sw = 0f; val acc = FloatArray(4)
                for (dy in 0..1) for (dx in 0..1) { val q = minOf(ph - 1, y * 2 + dy) * pw + minOf(pw - 1, x * 2 + dx)
                    val ww = parent(l, q, 4); if (ww <= 0f) continue; sw += ww; for (k in 0..3) acc[k] += parent(l, q, k) * ww }
                if (sw > 0f) { for (k in 0..3) c[k][o] = acc[k] / sw; c[4][o] = sw } }
            ws += nw; hs += nh; ch += c
        }
        // pull: unused texels take the (already filled) coarser value, coarsest first
        for (l in ws.size - 2 downTo 1) { val lw = ws[l]; val cw = ws[l + 1]; val chh = hs[l + 1]; val c = ch[l - 1]; val p = ch[l]
            for (y in 0 until hs[l]) for (x in 0 until lw) { val o = y * lw + x; if (c[4][o] > 0f) continue
                val q = minOf(chh - 1, y / 2) * cw + minOf(cw - 1, x / 2); for (k in 0..3) c[k][o] = p[k][q] } }
        fun pack(c: Array<FloatArray>, i: Int) = (c[3][i].toInt().coerceIn(0, 255) shl 24) or (c[0][i].toInt().coerceIn(0, 255) shl 16) or (c[1][i].toInt().coerceIn(0, 255) shl 8) or c[2][i].toInt().coerceIn(0, 255)
        val out = ArrayList<Level>()
        run { val px = argb.copyOf(); val cw = ws[1]; val c1 = ch[0]
            for (y in 0 until h) for (x in 0 until w) { val o = y * w + x; if (!used[o]) px[o] = pack(c1, minOf(hs[1] - 1, y / 2) * cw + minOf(cw - 1, x / 2)) }
            out += Level(w, h, px) }
        for (l in 1 until ws.size) out += Level(ws[l], hs[l], IntArray(ws[l] * hs[l]) { pack(ch[l - 1], it) })
        return out
    }

    /**
     * How far each level drifts from the full texture, as the mean RGB difference (0–255) over the texels the mesh
     * uses. Tightly packed, fragmented atlases (generated models) blur into neighbouring islands quickly, so this is
     * what limits how coarse a level the renderer may sample (see [safeLevel]).
     */
    fun levelErrors(levels: List<Level>, used: BooleanArray): FloatArray {
        val l0 = levels[0]; val w = l0.w; val h = l0.h; val out = FloatArray(levels.size)
        val step = maxOf(1, (w * h / 1_000_000))   // sample at most ~1M texels
        for (l in 1 until levels.size) { val lv = levels[l]; var e = 0.0; var c = 0L; var o = 0
            while (o < w * h) { if (used[o]) { val x = o % w; val y = o / w; val a = l0.px[o]; val b = lv.px[minOf(lv.h - 1, y * lv.h / h) * lv.w + minOf(lv.w - 1, x * lv.w / w)]
                e += (kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) + kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) + kotlin.math.abs((a and 255) - (b and 255))) / 3.0; c++ }
                o += step }
            out[l] = (e / maxOf(1L, c)).toFloat() }
        return out
    }

    /** Coarsest level whose [levelErrors] stays at or below [limit]: sampling any coarser smears the texture. */
    fun safeLevel(errors: FloatArray, limit: Float = SAFE_ERROR): Int { var l = 0; while (l + 1 < errors.size && errors[l + 1] <= limit) l++; return l }
    const val SAFE_ERROR = 6f

    /** Plain 2×2 box chain (what glGenerateMipmap does), for comparison in tests. */
    fun box(w: Int, h: Int, argb: IntArray): List<Level> = build(w, h, argb, BooleanArray(w * h) { true })
}
