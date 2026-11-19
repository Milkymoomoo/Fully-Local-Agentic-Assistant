package app.belle.assistant.threed

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Colour for the decoded Pixal3D mesh, on the phone, without the TRELLIS.2 texture model: Pixal3D is pixel-aligned, so
 * every surface point the camera saw maps back to a pixel of the crop the model was conditioned on (same camera as
 * [P3Math.uvNdc]: FOV from MoGe-2, object in the unit cube, camera on +z). Points the camera saw (depth test against a
 * z-buffer of the mesh, facing the camera, inside the subject mask) take the crop's colour; the rest (the back and
 * hidden folds) are filled from the nearest seen colours across the surface. Vertex colours (COLOR_0).
 */
object TextureBake {
    /** Vertex colours (native memory, close it) and how many vertices were seen / filled / coloured through the body. */
    class Result(val colors: Big, val seen: Int, val filled: Int, val through: Int = 0) : AutoCloseable { override fun close() = colors.close() }

    /** Projects a point in the unit cube to crop pixel coordinates (x, y in [0, size)) and its camera depth. */
    class Camera(fovDeg: Double, val size: Int) {
        private val t = tan(Math.toRadians(fovDeg) / 2)
        val d = (0.5 / t).toFloat(); val f = (0.5 * size / t).toFloat()
        fun depth(z: Float) = d - z
        fun px(x: Float, z: Float) = f * x / (d - z) + size / 2f
        fun py(y: Float, z: Float) = -f * y / (d - z) + size / 2f
    }

    // Everything sized by the mesh (millions of vertices / triangles) is a [Big] in native memory; only image-sized
    // arrays (the 1024² z-buffer, 4 MB) stay on the Java heap.

    fun normals(pos: Big, idx: Big): Big {
        val n = Big.floats(pos.size); val nf = n.f; val p = pos.f; val ix = idx.i
        for (t in 0 until idx.size / 3) {
            val a = ix.get(t * 3) * 3; val b = ix.get(t * 3 + 1) * 3; val c = ix.get(t * 3 + 2) * 3
            val ux = p.get(b) - p.get(a); val uy = p.get(b + 1) - p.get(a + 1); val uz = p.get(b + 2) - p.get(a + 2)
            val vx = p.get(c) - p.get(a); val vy = p.get(c + 1) - p.get(a + 1); val vz = p.get(c + 2) - p.get(a + 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            add3(nf, a, nx, ny, nz); add3(nf, b, nx, ny, nz); add3(nf, c, nx, ny, nz)
        }
        for (i in 0 until pos.size / 3) { val x = nf.get(i * 3); val y = nf.get(i * 3 + 1); val z = nf.get(i * 3 + 2)
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-12f); nf.put(i * 3, x / l); nf.put(i * 3 + 1, y / l); nf.put(i * 3 + 2, z / l) }
        return n
    }
    private fun add3(f: java.nio.FloatBuffer, o: Int, x: Float, y: Float, z: Float) { f.put(o, f.get(o) + x); f.put(o + 1, f.get(o + 1) + y); f.put(o + 2, f.get(o + 2) + z) }
    private fun cell(v: Float, grid: Int) = ((v + 0.5f) * grid).toInt().coerceIn(0, grid - 1).toLong()
    fun normals(pos: FloatArray, idx: IntArray): FloatArray = Big.of(pos).use { p -> Big.of(idx).use { i -> normals(p, i).use { it.toFloatArray() } } }

    /** Smallest camera depth per pixel of a [zs]×[zs] buffer (triangles rasterised at their projected position). */
    fun zbuffer(pos: Big, idx: Big, cam: Camera, zs: Int): FloatArray {
        val zb = FloatArray(zs * zs) { Float.MAX_VALUE }; val k = zs.toFloat() / cam.size
        val nv = pos.size / 3; val p = pos.f; val ix = idx.i
        Big.floats(nv * 3).use { sb -> val s = sb.f   // screen x, y and depth per vertex
            for (i in 0 until nv) { val x = p.get(i * 3); val y = p.get(i * 3 + 1); val z = p.get(i * 3 + 2)
                s.put(i * 3, cam.px(x, z) * k); s.put(i * 3 + 1, cam.py(y, z) * k); s.put(i * 3 + 2, cam.depth(z)) }
            for (t in 0 until idx.size / 3) {
                val a = ix.get(t * 3); val b = ix.get(t * 3 + 1); val c = ix.get(t * 3 + 2)
                val ax = s.get(a * 3); val ay = s.get(a * 3 + 1); val ad = s.get(a * 3 + 2)
                val bx = s.get(b * 3); val by = s.get(b * 3 + 1); val bd = s.get(b * 3 + 2)
                val cx0 = s.get(c * 3); val cy0 = s.get(c * 3 + 1); val cd = s.get(c * 3 + 2)
                val x0 = max(0, min(ax, min(bx, cx0)).toInt()); val x1 = min(zs - 1, max(ax, max(bx, cx0)).toInt())
                val y0 = max(0, min(ay, min(by, cy0)).toInt()); val y1 = min(zs - 1, max(ay, max(by, cy0)).toInt())
                if (x0 > x1 || y0 > y1) continue
                val area = (bx - ax) * (cy0 - ay) - (cx0 - ax) * (by - ay)
                if (kotlin.math.abs(area) < 1e-12f) {   // degenerate / sub-pixel: its vertices still occlude
                    for (v in intArrayOf(a, b, c)) { val px = s.get(v * 3).toInt(); val py = s.get(v * 3 + 1).toInt(); val dv = s.get(v * 3 + 2)
                        if (px in 0 until zs && py in 0 until zs) { val o = py * zs + px; if (dv < zb[o]) zb[o] = dv } }
                    continue
                }
                for (py in y0..y1) for (px in x0..x1) {
                    val cx = px + 0.5f; val cy = py + 0.5f
                    val w0 = ((bx - cx) * (cy0 - cy) - (cx0 - cx) * (by - cy)) / area
                    val w1 = ((cx0 - cx) * (ay - cy) - (ax - cx) * (cy0 - cy)) / area
                    val w2 = 1f - w0 - w1
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                    val dd = w0 * ad + w1 * bd + w2 * cd; val o = py * zs + px
                    if (dd < zb[o]) zb[o] = dd
                }
            }
        }
        return zb
    }

    /**
     * [img] the crop (RGB 0..1, [P3Math.Img] at any size, square), [mask] its subject mask at the same size (null = all
     * subject). [eps] depth tolerance in unit-cube units (≈ 2 voxels of the 512 grid).
     */
    fun bake(pos: Big, idx: Big, img: P3Math.Img, mask: FloatArray?, fovDeg: Double, zs: Int = 1024, eps: Float = 0.004f,
             throughProjection: Boolean = true): Result {
        val nv = pos.size / 3; val cam = Camera(fovDeg, img.w); val zb = zbuffer(pos, idx, cam, zs); val k = zs.toFloat() / img.w
        val p = pos.f; val colB = Big.floats(nv * 3); val col = colB.f; val known = BooleanArray(nv); var seen = 0
        // hidden points (the back, behind an arm) whose line of sight still lands on the subject take the colour seen
        // there ("through" the body: the back of a dress gets the dress colour); the rest is filled across the surface
        val through = BooleanArray(nv); var nThrough = 0
        try {
            normals(pos, idx).use { nb -> val nrm = nb.f
                for (i in 0 until nv) {
                    val x = p.get(i * 3); val y = p.get(i * 3 + 1); val z = p.get(i * 3 + 2)
                    val px = cam.px(x, z); val py = cam.py(y, z); val dep = cam.depth(z)
                    // the view ray from the camera (0,0,d) to the point is (x, y, z - d)
                    val vx = -x; val vy = -y; val vz = cam.d - z; val vl = sqrt(vx * vx + vy * vy + vz * vz)
                    // not at a grazing angle (either winding: the depth test below decides front or back)
                    if (kotlin.math.abs(nrm.get(i * 3) * vx + nrm.get(i * 3 + 1) * vy + nrm.get(i * 3 + 2) * vz) / vl < 0.05f) continue
                    val zx = (px * k).toInt(); val zy = (py * k).toInt(); if (zx !in 0 until zs || zy !in 0 until zs) continue
                    val onSubject = mask == null || bilinear(mask, img.w, img.h, px, py) >= 0.5f
                    if (throughProjection && onSubject) { through[i] = true }
                    // its own pixel; tolerance: eps plus what a 45° surface changes in depth over 1.5 pixels
                    val pixel = dep / cam.f / k
                    if (dep > zb[zy * zs + zx] + eps + 1.5f * pixel) continue
                    if (!onSubject) continue
                    for (c in 0..2) col.put(i * 3 + c, bilinear(img.px[c], img.w, img.h, px, py))
                    known[i] = true; through[i] = false; seen++
                }
            }
            for (i in 0 until nv) if (through[i]) { val x = p.get(i * 3); val y = p.get(i * 3 + 1); val z = p.get(i * 3 + 2)
                val px = cam.px(x, z); val py = cam.py(y, z)
                for (c in 0..2) col.put(i * 3 + c, bilinear(img.px[c], img.w, img.h, px, py)); known[i] = true; nThrough++ }
            val filled = fill(nv, idx, colB, known)
            smooth(nv, idx, colB, 2)   // single-pixel speckle (silhouette pixels, thin dark lines) → its neighbours
            return Result(colB, seen, filled, nThrough)
        } catch (e: Throwable) { colB.close(); throw e }
    }

    /** Array version (tests and small meshes): colours as an array. */
    class ArrayResult(val colors: FloatArray, val seen: Int, val filled: Int, val through: Int)
    fun bake(pos: FloatArray, idx: IntArray, img: P3Math.Img, mask: FloatArray?, fovDeg: Double, zs: Int = 1024, eps: Float = 0.004f,
             throughProjection: Boolean = true): ArrayResult = Big.of(pos).use { p -> Big.of(idx).use { i ->
        bake(p, i, img, mask, fovDeg, zs, eps, throughProjection).use { ArrayResult(it.colors.toFloatArray(), it.seen, it.filled, it.through) } } }

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val fx = (x - 0.5f).coerceIn(0f, (w - 1).toFloat()); val fy = (y - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1); val tx = fx - x0; val ty = fy - y0
        return (a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx) * (1 - ty) + (a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx) * ty
    }

    /** Vertex → neighbour lists (CSR, native): deg has nv + 1 offsets into adj. Both directions of every triangle edge. */
    private class Adjacency(nv: Int, idx: Big) : AutoCloseable {
        val deg = Big.ints(nv + 1); val adj: Big
        init {
            val d = deg.i; val ix = idx.i
            for (t in 0 until idx.size) d.put(ix.get(t) + 1, d.get(ix.get(t) + 1) + 2)
            for (i in 0 until nv) d.put(i + 1, d.get(i + 1) + d.get(i))
            adj = Big.ints(d.get(nv)); val a = adj.i
            Big.ints(nv + 1).use { fp -> val f = fp.i; for (i in 0..nv) f.put(i, d.get(i))
                for (t in 0 until idx.size / 3) for (e in 0..2) { val x = ix.get(t * 3 + e); val y = ix.get(t * 3 + (e + 1) % 3)
                    val px = f.get(x); a.put(px, y); f.put(x, px + 1); val py = f.get(y); a.put(py, x); f.put(y, py + 1) } }
        }
        override fun close() { deg.close(); adj.close() }
    }

    /** Breadth-first from the seen vertices: each unseen vertex takes the mean of its already-coloured neighbours. */
    fun fill(nv: Int, idx: Big, colB: Big, known: BooleanArray): Int {
        val col = colB.f
        if (known.none { it }) { for (i in 0 until colB.size) col.put(i, 0.6f); return nv }
        var filled = 0; val done = known.copyOf()
        Adjacency(nv, idx).use { g -> val deg = g.deg.i; val adj = g.adj.i
            var cur = Big.ints(nv); var nxt = Big.ints(nv)
            try {
                var nc = 0; for (i in 0 until nv) if (known[i]) cur.i.put(nc++, i)
                while (nc > 0) {
                    val ci = cur.i; val xi = nxt.i; var nn = 0
                    for (q in 0 until nc) { val v = ci.get(q); for (j in deg.get(v) until deg.get(v + 1)) { val u = adj.get(j); if (!done[u]) { done[u] = true; xi.put(nn++, u) } } }
                    for (q in 0 until nn) { val u = xi.get(q); var r = 0f; var gg = 0f; var b = 0f; var n = 0
                        for (j in deg.get(u) until deg.get(u + 1)) { val w = adj.get(j); if (known[w]) { r += col.get(w * 3); gg += col.get(w * 3 + 1); b += col.get(w * 3 + 2); n++ } }
                        if (n > 0) { col.put(u * 3, r / n); col.put(u * 3 + 1, gg / n); col.put(u * 3 + 2, b / n) } }
                    for (q in 0 until nn) known[xi.get(q)] = true
                    filled += nn; val t = cur; cur = nxt; nxt = t; nc = nn
                }
            } finally { cur.close(); nxt.close() }
        }
        // parts that touch nothing seen (loose pieces): the mean seen colour
        val mean = FloatArray(3); var ns = 0
        for (i in 0 until nv) if (known[i]) { ns++; for (c in 0..2) mean[c] += col.get(i * 3 + c) }
        for (i in 0 until nv) if (!done[i]) { filled++; for (c in 0..2) col.put(i * 3 + c, mean[c] / ns) }
        return filled
    }

    /** [iters] passes of colour = ½ own + ½ mean of the edge neighbours. */
    fun smooth(nv: Int, idx: Big, colB: Big, iters: Int) {
        val col = colB.f; val ix = idx.i
        Big.floats(nv * 3).use { accB -> Big.ints(nv).use { cntB -> val acc = accB.f; val cnt = cntB.i
            repeat(iters) {
                for (i in 0 until nv * 3) acc.put(i, 0f); for (i in 0 until nv) cnt.put(i, 0)
                for (t in 0 until idx.size / 3) for (e in 0..2) { val a = ix.get(t * 3 + e); val b = ix.get(t * 3 + (e + 1) % 3)
                    for (c in 0..2) { acc.put(a * 3 + c, acc.get(a * 3 + c) + col.get(b * 3 + c)); acc.put(b * 3 + c, acc.get(b * 3 + c) + col.get(a * 3 + c)) }
                    cnt.put(a, cnt.get(a) + 1); cnt.put(b, cnt.get(b) + 1) }
                for (i in 0 until nv) { val n = cnt.get(i); if (n > 0) for (c in 0..2) col.put(i * 3 + c, 0.5f * col.get(i * 3 + c) + 0.5f * acc.get(i * 3 + c) / n) }
            }
        } }
    }

    /** A clustered mesh (native memory; close it). [colors] null when there were none. */
    class Clustered(val pos: Big, val colors: Big?, val idx: Big) : AutoCloseable {
        override fun close() { pos.close(); colors?.close(); idx.close() }
        fun toArrays() = Triple(pos.toFloatArray(), colors?.toFloatArray(), idx.toIntArray())
    }

    /** Vertex clustering on a [grid]³ lattice over the unit cube: merged position / colour = mean; degenerate and
     *  duplicate triangles dropped. One pass, with native hash tables (the 0.1.3 version used a HashMap of boxed Longs,
     *  ~100 bytes of Java heap per vertex). Vertex numbering = first appearance, as before. */
    fun cluster(pos: Big, col: Big?, idx: Big, grid: Int): Clustered {
        val nv = pos.size / 3; val p = pos.f; val ix = idx.i
        Big.ints(nv).use { repB -> val rep = repB.i
            val m = BigLongIntMap(nv).use { map ->
                for (i in 0 until nv) {
                    val key = (cell(p.get(i * 3), grid) * grid + cell(p.get(i * 3 + 1), grid)) * grid + cell(p.get(i * 3 + 2), grid)
                    rep.put(i, map.getOrPut(key, map.size)) }
                map.size }
            val pb = Big.floats(m * 3); val cb = col?.let { Big.floats(m * 3) }
            try {
                val po = pb.f; val co = cb?.f; val ci = col?.f
                Big.ints(m).use { cntB -> val cnt = cntB.i
                    for (i in 0 until nv) { val r = rep.get(i); cnt.put(r, cnt.get(r) + 1)
                        for (k in 0..2) { po.put(r * 3 + k, po.get(r * 3 + k) + p.get(i * 3 + k)); if (co != null) co.put(r * 3 + k, co.get(r * 3 + k) + ci!!.get(i * 3 + k)) } }
                    for (r in 0 until m) { val c = cnt.get(r).toFloat(); for (k in 0..2) { po.put(r * 3 + k, po.get(r * 3 + k) / c); if (co != null) co.put(r * 3 + k, co.get(r * 3 + k) / c) } }
                }
                var o = 0
                val out = Big.ints(idx.size).use { all -> val a = all.i
                    BigLongIntMap(idx.size / 3).use { seenTri ->
                        for (t in 0 until idx.size / 3) {
                            val x = rep.get(ix.get(t * 3)); val y = rep.get(ix.get(t * 3 + 1)); val z = rep.get(ix.get(t * 3 + 2))
                            if (x == y || y == z || x == z) continue
                            val lo = minOf(x, y, z).toLong(); val hi = maxOf(x, y, z).toLong(); val mid = x.toLong() + y + z - lo - hi
                            if (!seenTri.add((lo * m + mid) * m + hi)) continue
                            a.put(o++, x); a.put(o++, y); a.put(o++, z)
                        }
                    }
                    all.prefix(o)
                }
                return Clustered(pb, cb, out)
            } catch (e: Throwable) { pb.close(); cb?.close(); throw e }
        }
    }
    /** Array version (tests and small meshes). */
    fun cluster(pos: FloatArray, col: FloatArray?, idx: IntArray, grid: Int): Triple<FloatArray, FloatArray?, IntArray> =
        Big.of(pos).use { p -> Big.of(idx).use { i -> (col?.let { Big.of(it) }).let { c ->
            try { cluster(p, c, i, grid).use { it.toArrays() } } finally { c?.close() } } } }
}
