package app.belle.assistant.threed

import java.io.File
import kotlin.math.exp
import kotlin.math.ln1p

/**
 * TRELLIS.2 shape VAE decoder on the phone: 5 ONNX segments (trellis2_shape_dec_seg0..4.onnx, see
 * tools/pixal3d_port/flow/shape_dec_export.py) with the data-dependent parts here: the submanifold-conv neighbour
 * map of every level, the learned 2× subdivision between levels, and the flexible dual grid → triangle mesh.
 * 32³ voxels → 512³ surface voxels → mesh in the unit cube [-0.5, 0.5]³ (Y up, like the reference).
 */
class ShapeDecoder(private val dir: File, private val threads: Int = Port3D.threads(), private val record: (Port3D.Stat) -> Unit = {}) {
    /** The decoded mesh, in native memory ([Big]): ~1.3 M vertices / 2.7 M triangles for a 3,800-voxel model. Close it when done. */
    class Mesh(val vertices: Big, val triangles: Big, val voxels: Int) : AutoCloseable {
        val nv get() = vertices.size / 3; val nt get() = triangles.size / 3
        override fun close() { vertices.close(); triangles.close() }
    }

    private fun <T> stage(name: String, block: () -> T): T { val (r, s) = Port3D.measure(name, block = block); record(s); return r }

    /**
     * [latent] [N × 32] straight from the sampler (normalised); [coords] (x,y,z) at 32³.
     * Every level's coordinates, neighbour map, features and model outputs live in native memory and are freed as soon as
     * the next level has what it needs, so the Java heap stays small however many voxels the surface has (the 0.1.3 version
     * kept them in Java arrays: 145 MB for one level's features on a 3,843-voxel model, over the 512 MB heap limit).
     */
    fun decode(latent: FloatArray, coords: IntArray): Mesh {
        val n0 = coords.size / 3
        var c = Big.of(coords)
        var a = Big.floats(latent.size).also { x -> val f = x.f; for (i in latent.indices) f.put(i, latent[i] * STD[i % 32] + MEAN[i % 32]) }
        var skip: Big? = null
        try {
            for (i in 0..4) {
                val n = c.size / 3
                val r = stage("shape dec seg$i N=$n") {
                    neighbours(c).use { nbr ->
                        Ort.Session(File(dir, "trellis2_shape_dec_seg$i.onnx").path, threads).use { s ->
                            val feeds = LinkedHashMap<String, Pair<Any, LongArray>>()
                            if (i == 0) feeds["x"] = a to longArrayOf(n.toLong(), 32)
                            else { val sk = skip!!; feeds["hg"] = a to longArrayOf(n.toLong(), (a.size / n).toLong()); feeds["skip"] = sk to longArrayOf(n.toLong(), (sk.size / n).toLong()) }
                            feeds["nbr"] = nbr to longArrayOf(n.toLong(), 27)
                            s.runBig(feeds)
                        }
                    }
                }
                a.close(); skip?.close(); skip = null
                try {
                    if (i == 4) { val out = r.getValue("out").data; return stage("dual grid mesh") { toMesh(c, out, 512, n0) } }
                    val sd = stage("subdivide L$i") { subdivide(c, r.getValue("sub").data, r.getValue("hc").data, r.getValue("h").data) }
                    c.close(); c = sd.coords; a = sd.hg; skip = sd.skip
                } finally { r.values.forEach { it.close() } }
            }
            error("unreachable")
        } finally { c.close(); a.close(); skip?.close(); Ort.trim() }
    }

    /** One level's children: coordinates and the two feature tensors (native memory; the caller closes them). */
    class Sub(val coords: Big, val hg: Big, val skip: Big) : AutoCloseable { override fun close() { coords.close(); hg.close(); skip.close() } }

    companion object {
        private const val S = 1024L
        private fun key(x: Int, y: Int, z: Int) = (x * S + y) * S + z

        /** Voxel coordinates → row, in a native hash table (the 0.1.3 version sorted boxed Integers on the Java heap). */
        class Lookup(coords: Big) : AutoCloseable {
            private val map = BigLongIntMap(coords.size / 3)
            init { val c = coords.i; for (r in 0 until coords.size / 3) map.getOrPut(key(c.get(r * 3), c.get(r * 3 + 1), c.get(r * 3 + 2)), r) }
            fun find(x: Int, y: Int, z: Int): Int {
                if (x < 0 || y < 0 || z < 0 || x >= S || y >= S || z >= S) return -1
                return map.get(key(x, y, z))
            }
            override fun close() = map.close()
        }

        /** [N × 27] neighbour rows for a 3³ submanifold conv, offset order (dx,dy,dz) x-major like flexgemm; missing → N. */
        fun neighbours(coords: Big): Big {
            val n = coords.size / 3; val out = Big.longs(n * 27); val o = out.l; val cs = coords.i
            Lookup(coords).use { lk ->
                for (i in 0 until n) { val x = cs.get(i * 3); val y = cs.get(i * 3 + 1); val z = cs.get(i * 3 + 2); var v = 0
                    for (a in -1..1) for (b in -1..1) for (d in -1..1) { val r = lk.find(x + a, y + b, z + d); o.put(i * 27 + v, if (r < 0) n.toLong() else r.toLong()); v++ } }
            }
            return out
        }
        fun neighbours(coords: IntArray): LongArray = Big.of(coords).use { c -> neighbours(c).use { it.toLongArray() } }

        /** SparseResBlockC2S3d's subdivision: children where sub > 0 (row-major over [N × 8]); child bit k offsets axis k. */
        fun subdivide(coords: Big, sub: Big, hc: Big, h: Big): Sub {
            val n = coords.size / 3; var m = 0; val sb = sub.f; for (f in 0 until sub.size) if (sb.get(f) > 0f) m++
            val ch = hc.size / (n * 8); val cs = h.size / (n * 8)
            val nc = Big.ints(m * 3); val hg = Big.floats(m * ch); val sk = Big.floats(m * cs)
            val ci = coords.i; val nci = nc.i; val hcf = hc.f; val hf = h.f; val hgf = hg.f; val skf = sk.f; var j = 0
            for (f in 0 until sub.size) if (sb.get(f) > 0f) {
                val p = f / 8; val bits = f % 8
                for (k in 0..2) nci.put(j * 3 + k, ci.get(p * 3 + k) * 2 + ((bits shr k) and 1))
                for (q in 0 until ch) hgf.put(j * ch + q, hcf.get(f * ch + q))
                for (q in 0 until cs) skf.put(j * cs + q, hf.get(f * cs + q))
                j++
            }
            return Sub(nc, hg, sk)
        }
        /** Array version (tests). */
        class SubArrays(val coords: IntArray, val hg: FloatArray, val skip: FloatArray)
        fun subdivide(coords: IntArray, sub: FloatArray, hc: FloatArray, h: FloatArray): SubArrays =
            listOf(Big.of(coords), Big.of(sub), Big.of(hc), Big.of(h)).let { (c, s, a, b) ->
                try { subdivide(c, s, a, b).use { SubArrays(it.coords.toIntArray(), it.hg.toFloatArray(), it.skip.toFloatArray()) } }
                finally { c.close(); s.close(); a.close(); b.close() } }

        /** flexible_dual_grid_to_mesh (split-weight path): one dual vertex per voxel, a quad per flagged edge whose 4 voxels exist. */
        fun toMesh(coords: Big, out: Big, res: Int, n0: Int = 0): Mesh {
            val n = coords.size / 3; val v = Big.floats(n * 3); val cs = coords.i; val of = out.f; val vf = v.f
            Big.floats(n).use { wb -> val w = wb.f
                for (i in 0 until n) {
                    for (k in 0..2) vf.put(i * 3 + k, (cs.get(i * 3 + k) + (2f / (1f + exp(-of.get(i * 7 + k))) - 0.5f)) / res - 0.5f)
                    val q = of.get(i * 7 + 6); w.put(i, if (q > 20f) q else ln1p(exp(q)))
                }
                Lookup(coords).use { lk ->
                    // two passes (count, then fill) so the triangle array is allocated once at its exact size
                    val nq = quads(coords, out, lk, w, null)
                    val tris = Big.ints(nq * 6)
                    try { quads(coords, out, lk, w, tris.i) } catch (e: Throwable) { tris.close(); v.close(); throw e }
                    return Mesh(v, tris, n0)
                }
            }
        }

        private val OFFS = arrayOf(intArrayOf(0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0), intArrayOf(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1), intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0))
        /** The quads of flagged edges whose 4 voxels exist; writes 2 triangles each into [t] (null: just count). */
        private fun quads(coords: Big, out: Big, lk: Lookup, w: java.nio.FloatBuffer, t: java.nio.IntBuffer?): Int {
            val n = coords.size / 3; val cs = coords.i; val of = out.f; val q = IntArray(4); var nq = 0
            for (i in 0 until n) for (ax in 0..2) if (of.get(i * 7 + 3 + ax) > 0f) {
                var ok = true; val o = OFFS[ax]
                for (c in 0..3) { val r = lk.find(cs.get(i * 3) + o[c * 3], cs.get(i * 3 + 1) + o[c * 3 + 1], cs.get(i * 3 + 2) + o[c * 3 + 2]); if (r < 0) { ok = false; break }; q[c] = r }
                if (!ok) continue
                if (t != null) { val k = nq * 6; val a = q[0]; val b = q[1]; val c = q[2]; val d = q[3]
                    if (w.get(a) * w.get(c) > w.get(b) * w.get(d)) { t.put(k, a); t.put(k + 1, b); t.put(k + 2, c); t.put(k + 3, a); t.put(k + 4, c); t.put(k + 5, d) }
                    else { t.put(k, a); t.put(k + 1, b); t.put(k + 2, d); t.put(k + 3, d); t.put(k + 4, b); t.put(k + 5, c) } }
                nq++
            }
            return nq
        }
        /** Array version (tests): vertices and triangles as arrays. */
        fun toMesh(coords: IntArray, out: FloatArray, res: Int, n0: Int = 0): Pair<FloatArray, IntArray> =
            Big.of(coords).use { c -> Big.of(out).use { o -> toMesh(c, o, res, n0).use { it.vertices.toFloatArray() to it.triangles.toIntArray() } } }

        /** Trellis2ShapeSLAT latent stats (process_out = latent · std + mean). */
        val MEAN = floatArrayOf(0.781296f, 0.018091f, -0.495192f, -0.558457f, 1.060530f, 0.093252f, 1.518149f, -0.933218f, -0.732996f, 2.604095f, -0.118341f, -2.143904f, 0.495076f, -2.179512f, -2.130751f, -0.996944f, 0.261421f, -2.217463f, 1.260067f, -0.150213f, 3.790713f, 1.481266f, -1.046058f, -1.523667f, -0.059621f, 2.220780f, 1.621212f, 0.877230f, 0.567247f, -3.175944f, -3.186688f, 1.578665f)
        val STD = floatArrayOf(5.972266f, 4.706852f, 5.445010f, 5.209927f, 5.320220f, 4.547237f, 5.020802f, 5.444004f, 5.226681f, 5.683095f, 4.831436f, 5.286469f, 5.652043f, 5.367606f, 5.525084f, 4.730578f, 4.805265f, 5.124013f, 5.530808f, 5.619001f, 5.103930f, 5.417670f, 5.269677f, 5.547194f, 5.634698f, 5.235274f, 6.110351f, 5.511298f, 6.237273f, 4.879207f, 5.347008f, 5.405691f)
    }
}
