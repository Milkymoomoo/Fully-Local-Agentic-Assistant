package app.belle.assistant.mesh

import kotlin.math.sqrt

/**
 * Progressive mesh (Hoppe-style) built from quadric-error half-edge collapses, computed ONCE after a mesh is
 * created or imported, and stored inside the same .glb.
 *
 * Half-edge collapses never create vertices: a collapsed vertex u just merges into an existing neighbour v.
 * So every surviving vertex keeps its own position, UV, colour and — importantly — its skinning joints and
 * weights, which is how rig weights survive any detail level.
 *
 * Storage layout after [build]: vertices are renumbered so that the vertex collapsed LAST comes first (survivors
 * at the front). parent[i] < i for every collapsed vertex. Triangles are sorted by the vertex number whose
 * collapse destroys them (triDeath, -1 = never), ascending. For a detail level of n vertices:
 *   • remap[i] = i if i < n else remap[parent[i]]      (one pass, O(n_total))
 *   • the live triangles are exactly the first k with triDeath < n (binary search), indices remapped.
 * So the renderer draws fewer triangles AND touches only vertices [0, n): fewer vertex-shader / skinning
 * invocations, all from one stored mesh.
 */
object ProgressiveMesh {
    /** Stored-table version; older tables in saved .glb files are ignored and rebuilt (v2: seam-safe wedges). */
    /** A collapsed triangle must stay within this cosine of its ORIGINAL facing (normal cone). */
    @JvmStatic var NORMAL_CONE = (System.getProperty("pm.cone") ?: "0.2").toDouble()
    /** Debug: (live triangles, collapse cost) pairs of the last build, in collapse order. */
    @Volatile var lastCosts = FloatArray(0)
    /** Relative simplification error (collapse cost / size⁴) allowed for the lowest detail level the slider reaches. */
    var CLEAN_ERROR = (System.getProperty("pm.clean") ?: "1.2e-9").toDouble()
    const val VERSION = 3   // 3: stores cleanTris
    private fun uvWrap(x: Float) = x - kotlin.math.floor(x)
    private val SAMPLES = arrayOf(floatArrayOf(1 / 3f, 1 / 3f, 1 / 3f), floatArrayOf(0.6f, 0.2f, 0.2f), floatArrayOf(0.2f, 0.6f, 0.2f), floatArrayOf(0.2f, 0.2f, 0.6f),
        floatArrayOf(0.45f, 0.45f, 0.1f), floatArrayOf(0.45f, 0.1f, 0.45f), floatArrayOf(0.1f, 0.45f, 0.45f))
    /**
     * Returns the same mesh reordered, with pmParent / pmTriDeath filled in. [minVertices] stops collapsing early.
     *
     * Collapses run on the WELDED surface (vertices that share a position are one "corner"), so UV seams and
     * normal splits never crack open: when corner u merges into corner v, every attribute copy ("wedge") of u moves
     * to the copy of v in the same texture chart, together. A collapse is refused when some copy of u has no
     * matching copy of v (it would drag a triangle across the atlas), when it would fold a triangle in 3D or in UV
     * space, or when it would pull an open border inward.
     * Storage: the copies of one corner get consecutive numbers; a triangle's death number is the LAST copy of the
     * corner that kills it, so [verticesForTriangles] always lands on whole corners.
     */
    @Synchronized fun build(m0: Mesh3D, minVertices: Int = 64, progress: (Float) -> Unit = {}): Mesh3D {
        val m = m0.withNormals()
        val V = m.vertexCount; val T = m.triCount; val pos = m.pos; val uv = m.uv
        // ---- weld: corner id per attribute vertex
        val bnd = m.bounds(); val q = maxOf(bnd[3] - bnd[0], bnd[4] - bnd[1], bnd[5] - bnd[2]).coerceAtLeast(1e-9f) / 1_000_000.0
        val cornerOf = IntArray(V); var C = 0
        run { val map = HashMap<Triple<Long, Long, Long>, Int>(V * 2)
            for (i in 0 until V) cornerOf[i] = map.getOrPut(Triple(Math.round(pos[i * 3] / q), Math.round(pos[i * 3 + 1] / q), Math.round(pos[i * 3 + 2] / q))) { C++ } }
        val cp = FloatArray(C * 3); for (i in 0 until V) { val c = cornerOf[i]; cp[c * 3] = pos[i * 3]; cp[c * 3 + 1] = pos[i * 3 + 1]; cp[c * 3 + 2] = pos[i * 3 + 2] }
        val atri = m.idx.copyOf()                                   // attribute-vertex triangles (updated by collapses)
        val ctri = IntArray(T * 3) { cornerOf[atri[it]] }           // corner triangles
        // ---- quadrics per corner
        val Q = DoubleArray(C * 10)
        fun addPlane(v: Int, a: Double, b: Double, c: Double, d: Double, w: Double) { val o = v * 10
            Q[o] += w * a * a; Q[o + 1] += w * a * b; Q[o + 2] += w * a * c; Q[o + 3] += w * a * d; Q[o + 4] += w * b * b
            Q[o + 5] += w * b * c; Q[o + 6] += w * b * d; Q[o + 7] += w * c * c; Q[o + 8] += w * c * d; Q[o + 9] += w * d * d }
        val fn = DoubleArray(T * 3); val degenerate = BooleanArray(T)
        for (t in 0 until T) {
            val a = ctri[t * 3] * 3; val b = ctri[t * 3 + 1] * 3; val c = ctri[t * 3 + 2] * 3
            val ux = (cp[b] - cp[a]).toDouble(); val uy = (cp[b + 1] - cp[a + 1]).toDouble(); val uz = (cp[b + 2] - cp[a + 2]).toDouble()
            val vx = (cp[c] - cp[a]).toDouble(); val vy = (cp[c + 1] - cp[a + 1]).toDouble(); val vz = (cp[c + 2] - cp[a + 2]).toDouble()
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz); if (l < 1e-20 || a == b || b == c || a == c) { degenerate[t] = a == b || b == c || a == c; continue }
            nx /= l; ny /= l; nz /= l; fn[t * 3] = nx; fn[t * 3 + 1] = ny; fn[t * 3 + 2] = nz
            val d = -(nx * cp[a] + ny * cp[a + 1] + nz * cp[a + 2])
            for (k in 0..2) addPlane(ctri[t * 3 + k], nx, ny, nz, d, l / 2)
        }
        fun area3(t: Int): Double { val a = ctri[t * 3] * 3; val b = ctri[t * 3 + 1] * 3; val c = ctri[t * 3 + 2] * 3
            val ux = (cp[b] - cp[a]).toDouble(); val uy = (cp[b + 1] - cp[a + 1]).toDouble(); val uz = (cp[b + 2] - cp[a + 2]).toDouble()
            val vx = (cp[c] - cp[a]).toDouble(); val vy = (cp[c + 1] - cp[a + 1]).toDouble(); val vz = (cp[c + 2] - cp[a + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx; return sqrt(nx * nx + ny * ny + nz * nz) }
        // ---- corner → triangles
        val vt = arrayOfNulls<IntArray>(C); val vtn = IntArray(C)
        fun vtAdd(v: Int, t: Int) { var a = vt[v]; if (a == null) { a = IntArray(6); vt[v] = a } else if (vtn[v] == a.size) { a = a.copyOf(a.size * 2); vt[v] = a }; a[vtn[v]++] = t }
        val alive = BooleanArray(T) { !degenerate[it] }
        for (t in 0 until T) if (alive[t]) for (k in 0..2) vtAdd(ctri[t * 3 + k], t)
        // ---- open borders of the welded surface: strong perpendicular planes, and border corners stay on the border
        val border = BooleanArray(C)
        for (v in 0 until C) { val ts = vt[v] ?: continue
            for (qq in 0 until vtn[v]) { val t = ts[qq]
                for (k in 0..2) { val a = ctri[t * 3 + k]; val b = ctri[t * 3 + (k + 1) % 3]; if (a != v) continue
                    var shared = 0; for (r in 0 until vtn[v]) { val t2 = ts[r]; if (t2 == t) continue; for (kk in 0..2) if (ctri[t2 * 3 + kk] == b) shared++ }
                    if (shared == 0) {
                        val ex = (cp[b * 3] - cp[a * 3]).toDouble(); val ey = (cp[b * 3 + 1] - cp[a * 3 + 1]).toDouble(); val ez = (cp[b * 3 + 2] - cp[a * 3 + 2]).toDouble()
                        var px = ey * fn[t * 3 + 2] - ez * fn[t * 3 + 1]; var py = ez * fn[t * 3] - ex * fn[t * 3 + 2]; var pz = ex * fn[t * 3 + 1] - ey * fn[t * 3]
                        val l = sqrt(px * px + py * py + pz * pz); if (l < 1e-20) continue; px /= l; py /= l; pz /= l
                        val d = -(px * cp[a * 3] + py * cp[a * 3 + 1] + pz * cp[a * 3 + 2]); val w = (ex * ex + ey * ey + ez * ez) * 100
                        addPlane(a, px, py, pz, d, w); addPlane(b, px, py, pz, d, w); border[a] = true; border[b] = true
                    } } } }
        fun err(o1: Int, o2: Int, x: Double, y: Double, z: Double): Double {
            val a = Q[o1] + Q[o2]; val b = Q[o1 + 1] + Q[o2 + 1]; val c = Q[o1 + 2] + Q[o2 + 2]; val d = Q[o1 + 3] + Q[o2 + 3]
            val e = Q[o1 + 4] + Q[o2 + 4]; val f = Q[o1 + 5] + Q[o2 + 5]; val g = Q[o1 + 6] + Q[o2 + 6]; val h = Q[o1 + 7] + Q[o2 + 7]
            val i = Q[o1 + 8] + Q[o2 + 8]; val j = Q[o1 + 9] + Q[o2 + 9]
            return a * x * x + 2 * b * x * y + 2 * c * x * z + 2 * d * x + e * y * y + 2 * f * y * z + 2 * g * y + h * z * z + 2 * i * z + j
        }
        // texel density (UV area per 3D area) around each attribute vertex: a collapsed triangle whose density jumps
        // well above its corners' has started to span a gap of the atlas (other charts show through as speckles)
        val dens = FloatArray(V)
        if (uv != null) { val su = DoubleArray(V); val sa = DoubleArray(V)
            for (t in 0 until T) { if (degenerate[t]) continue; val w = IntArray(3) { atri[t * 3 + it] }
                val ua = kotlin.math.abs((uv[w[1] * 2] - uv[w[0] * 2]) * (uv[w[2] * 2 + 1] - uv[w[0] * 2 + 1]) - (uv[w[2] * 2] - uv[w[0] * 2]) * (uv[w[1] * 2 + 1] - uv[w[0] * 2 + 1])).toDouble()
                val a3 = sqrt(fn[t * 3] * fn[t * 3] + fn[t * 3 + 1] * fn[t * 3 + 1] + fn[t * 3 + 2] * fn[t * 3 + 2]).let { if (it == 0.0) 0.0 else area3(t) }
                for (x in w) { su[x] += ua; sa[x] += a3 } }
            for (i in 0 until V) dens[i] = if (sa[i] > 0) (su[i] / sa[i]).toFloat() else 0f }
        // chart map: which atlas island (connected UV component) covers each texel, from the full-detail triangles.
        // A collapsed triangle must stay on its own island: on fragmented generated atlases a long triangle otherwise
        // cuts across a notch where other islands (other colours) sit, which shows as speckles at low detail.
        // (UV space shared by several islands, e.g. mirrored halves, keeps a second id; more than two = anything goes)
        val CR = 1024; val chartImg = IntArray(CR * CR) { -1 }; val chartImg2 = IntArray(CR * CR) { -1 }; val chartOf = IntArray(V)
        if (uv != null) {
            val par = IntArray(V) { it }
            fun find(x: Int): Int { var r = x; while (par[r] != r) r = par[r]; var y = x; while (par[y] != r) { val n = par[y]; par[y] = r; y = n }; return r }
            for (t in 0 until T) { if (degenerate[t]) continue; val a = find(atri[t * 3]); val b = find(atri[t * 3 + 1]); if (a != b) par[a] = b; val c = find(atri[t * 3 + 2]); val bb = find(atri[t * 3 + 1]); if (c != bb) par[c] = bb }
            for (i in 0 until V) chartOf[i] = find(i)
            for (t in 0 until T) { if (degenerate[t]) continue
                val w = IntArray(3) { atri[t * 3 + it] }; val id = chartOf[w[0]]
                val xs = FloatArray(3) { uvWrap(uv[w[it] * 2]) * CR }; val ys = FloatArray(3) { uvWrap(uv[w[it] * 2 + 1]) * CR }
                val x0 = (minOf(xs[0], xs[1], xs[2]).toInt() - 1).coerceAtLeast(0); val x1 = (maxOf(xs[0], xs[1], xs[2]).toInt() + 1).coerceAtMost(CR - 1)
                val y0 = (minOf(ys[0], ys[1], ys[2]).toInt() - 1).coerceAtLeast(0); val y1 = (maxOf(ys[0], ys[1], ys[2]).toInt() + 1).coerceAtMost(CR - 1)
                if ((x1 - x0) > CR / 2 || (y1 - y0) > CR / 2) continue
                val den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
                for (py in y0..y1) for (px in x0..x1) {
                    val inside = if (kotlin.math.abs(den) < 1e-9f) true else {
                        val fx = px + 0.5f; val fy = py + 0.5f
                        val b0 = ((ys[1] - ys[2]) * (fx - xs[2]) + (xs[2] - xs[1]) * (fy - ys[2])) / den; val b1 = ((ys[2] - ys[0]) * (fx - xs[2]) + (xs[0] - xs[2]) * (fy - ys[2])) / den
                        val e = 1.2f / sqrt(kotlin.math.abs(den.toDouble())).toFloat().coerceAtLeast(1e-3f)
                        b0 > -e && b1 > -e && 1 - b0 - b1 > -e }
                    if (inside) { val o = py * CR + px
                        when { chartImg[o] == -1 -> chartImg[o] = id; chartImg[o] == id || chartImg2[o] == id || chartImg2[o] == -2 -> {}
                            chartImg2[o] == -1 -> chartImg2[o] = id; else -> chartImg2[o] = -2 } }
                } }
        }
        fun onChart(id: Int, u0: Float, v0: Float): Boolean { val x = (uvWrap(u0) * CR).toInt().coerceIn(0, CR - 1); val y = (uvWrap(v0) * CR).toInt().coerceIn(0, CR - 1); val o = y * CR + x; return chartImg[o] == id || chartImg2[o] == id || chartImg2[o] == -2 }
        val wedgeMap = HashMap<Int, Int>()
        /** Can corner u merge into v? Fills [wedgeMap] (copy of u → copy of v) when it can. */
        fun valid(u: Int, v: Int): Boolean {
            val ts = vt[u] ?: return false; wedgeMap.clear()
            if (border[u]) { if (!border[v]) return false
                var shared = 0; for (qq in 0 until vtn[u]) { val t = ts[qq]; if (!alive[t]) continue; for (k in 0..2) if (ctri[t * 3 + k] == v) shared++ }
                if (shared != 1) return false }
            // wedge targets from the triangles that contain the edge
            for (qq in 0 until vtn[u]) { val t = ts[qq]; if (!alive[t]) continue
                var ku = -1; var kv = -1; for (k in 0..2) { if (ctri[t * 3 + k] == u) ku = k; if (ctri[t * 3 + k] == v) kv = k }
                if (kv < 0) continue
                val wu = atri[t * 3 + ku]; val wv = atri[t * 3 + kv]
                val prev = wedgeMap.put(wu, wv); if (prev != null && prev != wv) return false
            }
            // every other triangle of u: its copy of u needs a target; no 3D flip; no UV fold / big stretch
            for (qq in 0 until vtn[u]) { val t = ts[qq]; if (!alive[t]) continue
                var ku = -1; var hasV = false; for (k in 0..2) { if (ctri[t * 3 + k] == u) ku = k; if (ctri[t * 3 + k] == v) hasV = true }
                if (hasV) continue
                val wu = atri[t * 3 + ku]; val wv = wedgeMap[wu] ?: return false
                val p = IntArray(3) { if (it == ku) v else ctri[t * 3 + it] }
                val ux = cp[p[1] * 3] - cp[p[0] * 3]; val uy = cp[p[1] * 3 + 1] - cp[p[0] * 3 + 1]; val uz = cp[p[1] * 3 + 2] - cp[p[0] * 3 + 2]
                val vx = cp[p[2] * 3] - cp[p[0] * 3]; val vy = cp[p[2] * 3 + 1] - cp[p[0] * 3 + 1]; val vz = cp[p[2] * 3 + 2] - cp[p[0] * 3 + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val l = sqrt((nx * nx + ny * ny + nz * nz).toDouble()); if (l < 1e-20) return false
                if ((nx * fn[t * 3] + ny * fn[t * 3 + 1] + nz * fn[t * 3 + 2]) / l < NORMAL_CONE) return false
                if (uv != null) {
                    val w = IntArray(3) { if (it == ku) wv else atri[t * 3 + it] }; val o = IntArray(3) { atri[t * 3 + it] }
                    fun area(x: IntArray) = (uv[x[1] * 2] - uv[x[0] * 2]) * (uv[x[2] * 2 + 1] - uv[x[0] * 2 + 1]) - (uv[x[2] * 2] - uv[x[0] * 2]) * (uv[x[1] * 2 + 1] - uv[x[0] * 2 + 1])
                    val before = area(o); val after = area(w)
                    if (before * after <= 0f) return false
                    if (kotlin.math.abs(after) > kotlin.math.abs(before) * 4f + 1e-7f) return false
                    // interior sample points must all lie on this triangle's own island
                    val id = chartOf[w[0]]
                    for (bc in SAMPLES) { val su = bc[0] * uv[w[0] * 2] + bc[1] * uv[w[1] * 2] + bc[2] * uv[w[2] * 2]; val sv = bc[0] * uv[w[0] * 2 + 1] + bc[1] * uv[w[1] * 2 + 1] + bc[2] * uv[w[2] * 2 + 1]
                        if (!onChart(id, su, sv)) return false }
                    val ref = maxOf(dens[w[0]], dens[w[1]], dens[w[2]])
                    if (ref > 0f && kotlin.math.abs(after) > 2.5f * ref * l.toFloat() + 1e-9f) return false
                }
            }
            return true
        }
        val bestTo = IntArray(C) { -1 }; val bestCost = DoubleArray(C) { Double.MAX_VALUE }; val stamp = IntArray(C); val gone = BooleanArray(C)
        fun evaluate(u: Int) {
            bestTo[u] = -1; bestCost[u] = Double.MAX_VALUE
            val ts = vt[u] ?: return
            for (qq in 0 until vtn[u]) { val t = ts[qq]; if (!alive[t]) continue
                for (k in 0..2) { val v = ctri[t * 3 + k]; if (v == u || gone[v]) continue
                    val c = err(u * 10, v * 10, cp[v * 3].toDouble(), cp[v * 3 + 1].toDouble(), cp[v * 3 + 2].toDouble())
                    if (c < bestCost[u] && valid(u, v)) { bestCost[u] = c; bestTo[u] = v } } }
        }
        heapInit(C)
        for (u in 0 until C) { evaluate(u); if (bestTo[u] >= 0) heapPush(bestCost[u], u, stamp[u]) }
        // copies (attribute vertices) of each corner
        val copies = arrayOfNulls<IntArray>(C); run { val cnt = IntArray(C); for (i in 0 until V) cnt[cornerOf[i]]++
            for (c in 0 until C) copies[c] = IntArray(cnt[c]); java.util.Arrays.fill(cnt, 0); for (i in 0 until V) { val c = cornerOf[i]; copies[c]!![cnt[c]++] = i } }
        val parentOld = IntArray(V) { -1 }; val order = IntArray(C); var collapsed = 0
        val killer = IntArray(T) { -1 }                            // corner whose collapse removed the triangle
        var live = 0
        for (c in 0 until C) { var any = false; val l = vt[c]; if (l != null) for (r in 0 until vtn[c]) if (alive[l[r]]) { any = true; break }
            if (any) live++ else { gone[c] = true; order[collapsed++] = c } }
        for (t in 0 until T) if (degenerate[t]) killer[t] = -2
        val nbr = IntArray(256)
        var liveTris = 0; for (t in 0 until T) if (alive[t]) liveTris++
        val costLog = FloatArray(C * 2); var costN = 0
        while (heapSize > 0 && live > minVertices) {
            val u = heapTopV(); val st = heapTopS(); val topCost = heapTopCost(); heapPop()
            if (gone[u] || st != stamp[u] || bestTo[u] < 0) continue
            val v = bestTo[u]
            if (gone[v] || !valid(u, v)) { evaluate(u); stamp[u]++; if (bestTo[u] >= 0) heapPush(bestCost[u], u, stamp[u]); continue }
            val ts = vt[u]!!; var nn = 0
            for (qq in 0 until vtn[u]) { val t = ts[qq]; if (!alive[t]) continue
                var hasV = false; for (k in 0..2) if (ctri[t * 3 + k] == v) hasV = true
                if (hasV) { alive[t] = false; killer[t] = u; liveTris-- }
                else { for (k in 0..2) if (ctri[t * 3 + k] == u) { ctri[t * 3 + k] = v; atri[t * 3 + k] = wedgeMap[atri[t * 3 + k]]!! }; vtAdd(v, t) }
                for (k in 0..2) { val w = ctri[t * 3 + k]; if (w != u && nn < nbr.size && (0 until nn).none { nbr[it] == w }) nbr[nn++] = w }
            }
            for (c in copies[u]!!) parentOld[c] = wedgeMap[c] ?: -1
            for (k in 0..9) Q[v * 10 + k] += Q[u * 10 + k]
            gone[u] = true; order[collapsed++] = u; live--
            if (costN < C) { costLog[costN * 2] = liveTris.toFloat(); costLog[costN * 2 + 1] = topCost.toFloat(); costN++ }
            run { val a = vt[v]!!; var w = 0; for (r in 0 until vtn[v]) if (alive[a[r]]) a[w++] = a[r]; vtn[v] = w }
            if (vtn[v] == 0 && !gone[v]) { gone[v] = true; order[collapsed++] = v; live-- }
            for (r in 0 until nn) { val w = nbr[r]; if (gone[w]) continue
                val wl = vt[w]; var any = false; if (wl != null) for (z in 0 until vtn[w]) if (alive[wl[z]]) { any = true; break }
                if (!any) { gone[w] = true; order[collapsed++] = w; live--; continue }
                stamp[w]++; evaluate(w); if (bestTo[w] >= 0) heapPush(bestCost[w], w, stamp[w]) }
            if (collapsed % 2048 == 0) progress(collapsed.toFloat() / C)
        }
        // ---- numbering: surviving corners' copies first, then corners last-collapsed first, copies contiguous
        val newOf = IntArray(V); var next = 0; val lastOfCorner = IntArray(C)
        for (c in 0 until C) if (!gone[c]) { for (i in copies[c]!!) newOf[i] = next++; lastOfCorner[c] = next - 1 }
        for (r in collapsed - 1 downTo 0) { val c = order[r]; for (i in copies[c]!!) newOf[i] = next++; lastOfCorner[c] = next - 1 }
        val oldOf = IntArray(V); for (i in 0 until V) oldOf[newOf[i]] = i
        val parent = IntArray(V) { i -> val p = parentOld[oldOf[i]]; if (p < 0) -1 else newOf[p] }
        // a triangle dies at the LAST copy of its killer corner; degenerate input triangles never draw
        val triKey = IntArray(T) { t -> when { killer[t] == -2 -> V; killer[t] < 0 -> -1; else -> lastOfCorner[killer[t]] } }
        val triOrder = (0 until T).sortedBy { triKey[it] }
        val idx = IntArray(T * 3); val death = IntArray(T)
        for ((n, t) in triOrder.withIndex()) { for (k in 0..2) idx[n * 3 + k] = newOf[m.idx[t * 3 + k]]; death[n] = triKey[t] }
        fun permF(a: FloatArray?, c: Int) = a?.let { s -> FloatArray(s.size).also { d -> for (i in 0 until V) System.arraycopy(s, oldOf[i] * c, d, i * c, c) } }
        fun permI(a: IntArray?, c: Int) = a?.let { s -> IntArray(s.size).also { d -> for (i in 0 until V) System.arraycopy(s, oldOf[i] * c, d, i * c, c) } }
        lastCosts = costLog.copyOf(costN * 2)
        // The lowest level that still looks like the model: simplification error grows slowly and evenly until the
        // last collapses, which flatten creases and fold small details over the surface (they showed as specks).
        // Stop where the running maximum of the collapse error first passes CLEAN_ERROR × size⁴ (quadrics weigh
        // squared distance by area, so this is scale-free and the same for every model).
        val size = maxOf(bnd[3] - bnd[0], bnd[4] - bnd[1], bnd[5] - bnd[2]).toDouble(); val limit = CLEAN_ERROR * size * size * size * size
        var clean = liveTris; run { var mx = 0f; for (i in 0 until costN) { mx = maxOf(mx, costLog[i * 2 + 1]); if (mx > limit) break; clean = costLog[i * 2].toInt() } }
        progress(1f)
        return Mesh3D(permF(m.pos, 3)!!, permF(m.nrm, 3), permF(m.uv, 2), permF(m.col, 3), permI(m.joints, 4), permF(m.weights, 4), idx,
            m.texture, m.textureMime, parent, death).also { it.skeleton = m0.skeleton; it.animations = m0.animations; it.pmCleanTris = clean.coerceAtMost(T); it.joints2 = permI(m.joints2, 4); it.weights2 = permF(m.weights2, 4) }
    }

    /** Vertices that hold geometry (unreferenced ones are numbered last, with no parent). */
    fun usedVertices(m: Mesh3D): Int { val p = m.pmParent!!; val fc = firstCollapsed(p); var n = p.size; while (n > fc && p[n - 1] == -1) n--; return n }
    private fun firstCollapsed(p: IntArray): Int { val i = p.indexOfFirst { it >= 0 }; return if (i < 0) p.size else i }

    /** Index buffer for a level that keeps [n] vertices. Returns the triangle count written into [out]. */
    fun trim(m: Mesh3D, n: Int, out: IntArray, remap: IntArray = IntArray(m.vertexCount)): Int {
        val parent = m.pmParent!!; val death = m.pmTriDeath!!
        for (i in 0 until m.vertexCount) remap[i] = if (i < n || parent[i] < 0) i else remap[parent[i]]
        var lo = 0; var hi = death.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (death[mid] < n) lo = mid + 1 else hi = mid }
        var w = 0
        for (t in 0 until lo) { val a = remap[m.idx[t * 3]]; val b = remap[m.idx[t * 3 + 1]]; val c = remap[m.idx[t * 3 + 2]]
            if (a == b || b == c || a == c) continue; out[w * 3] = a; out[w * 3 + 1] = b; out[w * 3 + 2] = c; w++ }
        return w
    }

    /** Vertex count that gives roughly [tris] triangles (binary search over the stored order). */
    fun verticesForTriangles(m: Mesh3D, tris: Int): Int {
        val death = m.pmTriDeath!!
        if (tris >= death.size) return m.vertexCount
        val k = tris.coerceAtLeast(1)
        // below the coarsest stored level: keep exactly the never-collapsed vertices
        if (death[k - 1] < 0) return firstCollapsed(m.pmParent!!).coerceIn(1, m.vertexCount)
        return (death[k - 1] + 1).coerceIn(1, m.vertexCount)
    }

    // ---- binary min-heap of (cost, vertex, stamp) in flat arrays ----
    private var hc = DoubleArray(0); private var hv = IntArray(0); private var hs = IntArray(0); private var heapSize = 0
    private fun heapInit(n: Int) { hc = DoubleArray(n * 2); hv = IntArray(n * 2); hs = IntArray(n * 2); heapSize = 0 }
    private fun heapPush(c: Double, v: Int, s: Int) {
        if (heapSize == hc.size) { hc = hc.copyOf(heapSize * 2); hv = hv.copyOf(heapSize * 2); hs = hs.copyOf(heapSize * 2) }
        var i = heapSize++; hc[i] = c; hv[i] = v; hs[i] = s
        while (i > 0) { val p = (i - 1) / 2; if (hc[p] <= hc[i]) break; swap(i, p); i = p }
    }
    private fun heapTopV() = hv[0]; private fun heapTopS() = hs[0]; private fun heapTopCost() = hc[0]
    private fun heapPop() {
        heapSize--; if (heapSize == 0) return; hc[0] = hc[heapSize]; hv[0] = hv[heapSize]; hs[0] = hs[heapSize]; var i = 0
        while (true) { val l = i * 2 + 1; val r = l + 1; var m = i
            if (l < heapSize && hc[l] < hc[m]) m = l; if (r < heapSize && hc[r] < hc[m]) m = r; if (m == i) break; swap(i, m); i = m }
    }
    private fun swap(a: Int, b: Int) { val c = hc[a]; hc[a] = hc[b]; hc[b] = c; val v = hv[a]; hv[a] = hv[b]; hv[b] = v; val s = hs[a]; hs[a] = hs[b]; hs[b] = s }
}
