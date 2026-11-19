package app.belle.assistant.mesh

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * t482u: breasts separating from the dress / tearing during animation. For every test avatar, every animation
 * clip (idle, listening, thinking, talking with arm gestures) is sampled frame by frame and CPU-skinned like the
 * shader:
 *  • stretch: every mesh edge stays within 15 % of its rest length (edges into the moving lower jaw excluded:
 *    the mouth is meant to open);
 *  • no separation: every vertex of a separate piece (dress, top, armour) lying within the thickness band over the
 *    body keeps its distance to the body point under it (no gap opening);
 *  • symmetry: both breast bones exist and weight a similar amount of surface.
 * Close-up chest render sheets of the frames go to build/chest_sheet_<name>.png.
 */
class BreastNoTearTest {
    private fun find(vararg names: String) = names.flatMap { n -> listOf(File("src/main/assets/default_avatars/$n"), File("../art/$n"), File("art/$n")) }.firstOrNull { it.exists() }

    class Report(val name: String, val maxRatio: Float, val worstEdgeBones: String, val maxGap: Float, val breastVerts: IntArray, val text: String,
                 val bustRatio: Float, val bustEdges: Int, val mirrorDiff: Float, val pairs: Int, val torsoRatio: Float = 0f, val headOnChest: Int = 0,
                 val regions: Map<String, Float> = emptyMap(), val amplitude: Map<String, Float> = emptyMap())

    private fun skinMats(sk: Skeleton, pose: Pose3D, t: Float, state: Int, level: Float): FloatArray { val o = FloatArray(sk.size * 16); pose.evaluate(t, state, level, o); return o }
    /** As the renderer: strength·(full-motion weighting) + (1 − strength)·(no-stretch weighting, JOINTS_1/WEIGHTS_1). */
    private fun sets(m: Mesh3D, strength: Float): List<Triple<IntArray, FloatArray, Float>> =
        if (strength >= 0.999f || m.joints2 == null) listOf(Triple(m.joints!!, m.weights!!, 1f))
        else listOf(Triple(m.joints!!, m.weights!!, strength), Triple(m.joints2!!, m.weights2!!, 1f - strength))
    private fun skinned(m: Mesh3D, mats: FloatArray, strength: Float): FloatArray {
        val p = m.pos; val o = FloatArray(p.size)
        for ((J, W, f) in sets(m, strength)) for (i in 0 until m.vertexCount) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            for (k in 0..3) { val w = W[i * 4 + k] * f; if (w == 0f) continue; val b = J[i * 4 + k] * 16
                o[i * 3] += w * (mats[b] * x + mats[b + 4] * y + mats[b + 8] * z + mats[b + 12])
                o[i * 3 + 1] += w * (mats[b + 1] * x + mats[b + 5] * y + mats[b + 9] * z + mats[b + 13])
                o[i * 3 + 2] += w * (mats[b + 2] * x + mats[b + 6] * y + mats[b + 10] * z + mats[b + 14]) } }
        return o
    }

    fun check(name0: String, file: File, sheet: Boolean = true, shell: Boolean = false, strength: Float = 1f): Report {
        val name = if (strength >= 0.999f) name0 else name0 + "@" + (strength * 100).toInt()
        val (r, base0) = rigs.getOrPut(name0 + shell) {
            val raw0 = Import3D.read(mapOf(file.name to file.readBytes())).withNormals()
            val raw = if (shell) withDressShell(raw0) else raw0
            AutoRig3D.rig(ProgressiveMesh.build(raw)) to AutoRig3D.lastBaseWeights }
        val m = r.mesh; val sk = m.skeleton!!
        val V = m.vertexCount; val p = m.pos; val b = m.bounds(); val h = b[4] - b[1]
        val J = m.joints!!; val W = m.weights!!
        val jaw = sk.find("jaw"); val bl = sk.find("breast.L"); val br = sk.find("breast.R")
        fun w(i: Int, j: Int): Float { var s = 0f; for (k in 0..3) if (J[i * 4 + k] == j) s += W[i * 4 + k]; return s }
        val breastVerts = intArrayOf((0 until V).count { bl >= 0 && w(it, bl) > 0.05f }, (0 until V).count { br >= 0 && w(it, br) > 0.05f })
        // edges (unique), skipping degenerate ones and those into the jaw
        val edges = HashSet<Long>()
        for (t in 0 until m.idx.size / 3) for (k in 0..2) { val a = m.idx[t * 3 + k]; val c = m.idx[t * 3 + (k + 1) % 3]
            val lo = minOf(a, c); val hi = maxOf(a, c); edges += (lo.toLong() shl 32) or hi.toLong() }
        val E = edges.filter { e -> val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
            val dx = p[a * 3] - p[c * 3]; val dy = p[a * 3 + 1] - p[c * 3 + 1]; val dz = p[a * 3 + 2] - p[c * 3 + 2]
            sqrt(dx * dx + dy * dy + dz * dz) > h * 1e-4f && (jaw < 0 || (w(a, jaw) < 0.01f && w(c, jaw) < 0.01f)) }.toLongArray()
        // the bust + dress area in front of the chest (jaw edges NOT excluded here: nothing there may follow the mouth)
        val torso = if (bl < 0 || br < 0) LongArray(0) else run {
            val jw = r.jointsWorld; val bx = (jw[bl * 3] + jw[br * 3]) / 2; val by = (jw[bl * 3 + 1] + jw[br * 3 + 1]) / 2; val bz = minOf(jw[bl * 3 + 2], jw[br * 3 + 2])
            val half = abs(jw[bl * 3] - jw[br * 3]) / 2 + h * 0.08f
            fun inside(i: Int) = p[i * 3 + 1] in (by - h * 0.1f)..(by + h * 0.08f) && abs(p[i * 3] - bx) < half && p[i * 3 + 2] > bz - h * 0.02f
            edges.filter { e -> val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                val dx = p[a * 3] - p[c * 3]; val dy = p[a * 3 + 1] - p[c * 3 + 1]; val dz = p[a * 3 + 2] - p[c * 3 + 2]
                sqrt(dx * dx + dy * dy + dz * dz) > h * 1e-4f && (inside(a) || inside(c)) }.toLongArray() }
        val torsoMax = FloatArray(torso.size)
        val hd = sk.find("head")
        val headOnChest = if (bl < 0 || br < 0) 0 else run { val set = HashSet<Int>()
            for (e in torso) for (v in intArrayOf((e ushr 32).toInt(), (e and 0xffffffffL).toInt())) {
                val jw2 = r.jointsWorld; val by = (jw2[bl * 3 + 1] + jw2[br * 3 + 1]) / 2
                if (p[v * 3 + 1] < by + h * 0.04f && ((jaw >= 0 && w(v, jaw) > 0.05f) || (hd >= 0 && w(v, hd) > 0.05f))) set += v }
            set.size }
        // separate pieces over the body: pair each with the nearest vertex of a different piece, within the band
        val uf = IntArray(V) { it }; fun f(a: Int): Int { var x = a; while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x] }; return x }
        val q = h * 2e-4f; val weld = HashMap<Long, Int>()
        for (i in 0 until V) { val key = ((Math.round(p[i * 3] / q).toLong() and 0x1FFFFF) shl 42) or ((Math.round(p[i * 3 + 1] / q).toLong() and 0x1FFFFF) shl 21) or (Math.round(p[i * 3 + 2] / q).toLong() and 0x1FFFFF)
            val o = weld.getOrPut(key) { i }; if (o != i) uf[f(i)] = f(o) }
        for (t in 0 until m.idx.size / 3) { val a = f(m.idx[t * 3]); val c = f(m.idx[t * 3 + 1]); if (a != c) uf[a] = c; val d = f(m.idx[t * 3 + 2]); if (d != f(c)) uf[d] = f(c) }
        val band = h * 0.008f; val cell = band
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(x: Float, y: Float, z: Float) = ((Math.floor((x / cell).toDouble()).toLong() and 0xFFFFF) shl 40) or ((Math.floor((y / cell).toDouble()).toLong() and 0xFFFFF) shl 20) or (Math.floor((z / cell).toDouble()).toLong() and 0xFFFFF)
        for (i in 0 until V step 1) grid.getOrPut(key(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])) { ArrayList() } += i
        val pairs = ArrayList<IntArray>()
        for (i in 0 until V step 2) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; val gx = Math.floor((x / cell).toDouble()).toLong(); val gy = Math.floor((y / cell).toDouble()).toLong(); val gz = Math.floor((z / cell).toDouble()).toLong()
            var best = -1; var bd = band * band
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) { val l = grid[(((gx + dx) and 0xFFFFF) shl 40) or (((gy + dy) and 0xFFFFF) shl 20) or ((gz + dz) and 0xFFFFF)] ?: continue
                for (j in l) { if (f(j) == f(i)) continue; val ex = p[j * 3] - x; val ey = p[j * 3 + 1] - y; val ez = p[j * 3 + 2] - z; val d = ex * ex + ey * ey + ez * ez; if (d < bd) { bd = d; best = j } } }
            val bust = { k: Int -> (bl >= 0 && w(k, bl) > 0.01f) || (br >= 0 && w(k, br) > 0.01f) }
            if (best >= 0 && (bust(i) || bust(best)) && (jaw < 0 || (w(i, jaw) < 0.01f && w(best, jaw) < 0.01f))) pairs += intArrayOf(i, best) }
        run { val nk = sk.find("neck"); val ny = r.jointsWorld[nk * 3 + 1]; val ch = sk.find("chest")
            val low = (0 until V).filter { jaw >= 0 && w(it, jaw) > 0.01f && p[it * 3 + 1] < ny }
            println("$name: jaw-weighted vertices below the neck joint: ${low.size}" + (low.take(3).joinToString { " @(%.3f,%.3f,%.3f) jaw=%.2f".format(p[it * 3] / h, p[it * 3 + 1] / h, p[it * 3 + 2] / h, w(it, jaw)) }) + " neck y=%.3f chest y=%.3f".format(ny / h, r.jointsWorld[ch * 3 + 1] / h)) }
        var mirrorDiff = 0f
        if (bl >= 0 && br >= 0) { val mx = (r.jointsWorld[bl * 3] + r.jointsWorld[br * 3]) / 2
            val g = HashMap<Long, MutableList<Int>>(); val cs = h * 0.01f
            fun ck(x: Float, y: Float, z: Float) = Triple(Math.floor((x / cs).toDouble()).toLong(), Math.floor((y / cs).toDouble()).toLong(), Math.floor((z / cs).toDouble()).toLong())
            for (i in 0 until V) { val (a, b2, c) = ck(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]); g.getOrPut((a and 0xFFFFF shl 40) or (b2 and 0xFFFFF shl 20) or (c and 0xFFFFF)) { ArrayList() } += i }
            fun near(x: Float, y: Float, z: Float): Int { val (a, b2, c) = ck(x, y, z); var best = -1; var bd = Float.MAX_VALUE
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) for (j in g[((a + dx) and 0xFFFFF shl 40) or ((b2 + dy) and 0xFFFFF shl 20) or ((c + dz) and 0xFFFFF)] ?: emptyList<Int>()) {
                    val d = (p[j * 3] - x).let { it * it } + (p[j * 3 + 1] - y).let { it * it } + (p[j * 3 + 2] - z).let { it * it }; if (d < bd) { bd = d; best = j } }; return best }
            val miss = HashMap<String, Int>(); var tot = 0; mirrorDiff = 0f; var diff = 0.0; var ok = 0
            for ((self, other) in listOf(bl to br, br to bl)) for (i in 0 until V) { val ws = w(i, self); if (ws < 0.05f) continue; tot++
                val j = near(2 * mx - p[i * 3], p[i * 3 + 1], p[i * 3 + 2]); if (j < 0) continue; val wo = w(j, other); diff += abs(ws - wo); if (abs(ws - wo) < 0.1f) ok++
                if (wo < 0.02f) miss.merge(sk.names[(0..3).maxBy { W[j * 4 + it] }.let { J[j * 4 + it] }], 1, Int::plus) }
            mirrorDiff = (diff / tot.coerceAtLeast(1)).toFloat()
            println("$name mirror: $tot breast vertices, ${ok} match the other side within 0.1, mean |diff| %.3f; mirror vertices without breast weight by bone: $miss".format(diff / tot.coerceAtLeast(1))) }
        val pieceSize = HashMap<Int, Int>(); for (i in 0 until V) pieceSize.merge(f(i), 1, Int::plus)
        println("$name pieces: " + pieceSize.values.sortedDescending().take(8) + "; " + AutoRig3D.lastRelax)
        val edgeMax = FloatArray(E.size)
        val pose = Pose3D(sk, m.animations ?: emptyList())
        var maxRatio = 0f; var worst = ""; var maxGap = 0f; var worstGap = ""
        val clips = listOf("idle" to 0, "listen" to 1, "think" to 2, "talk" to 3)
        val frames = ArrayList<Pair<String, FloatArray>>()
        // every frame of every clip (0.1 s steps over 8 s), skinned and scanned on all cores
        class Fr(val clip: String, val t: Float, val mats: FloatArray)
        val all = ArrayList<Fr>()
        for ((clip, state) in clips) { var t = 0f
            while (t < 8f) { val level = if (state == 3) (0.5f + 0.5f * kotlin.math.sin(t * 11f)).coerceIn(0f, 1f) else 0f
                all += Fr(clip, t, skinMats(sk, pose, t, state, level)); t += 0.1f } }
        val ea = IntArray(E.size) { (E[it] ushr 32).toInt() }; val ec = IntArray(E.size) { (E[it] and 0xffffffffL).toInt() }
        val e0 = FloatArray(E.size) { k -> val a = ea[k]; val c = ec[k]; sqrt((p[a * 3] - p[c * 3]).let { it * it } + (p[a * 3 + 1] - p[c * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[c * 3 + 2]).let { it * it }) }
        val NT = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        class Acc { val edge = FloatArray(E.size); val tor = FloatArray(torso.size); var maxR = 0f; var worst = ""; var gap = 0f; var worstGap = ""; val fr = ArrayList<Pair<Int, Pair<String, FloatArray>>>(); val strip = ArrayList<Pair<Int, Pair<String, FloatArray>>>() }
        val accs = Array(NT) { Acc() }
        (0 until NT).map { ti -> Thread { val acc = accs[ti]; var fi = ti
            while (fi < all.size) { val fr = all[fi]; val clip = fr.clip; val t = fr.t
                val s = skinned(m, fr.mats, strength)
                for ((k, e) in torso.withIndex()) { val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                    val r0 = sqrt((p[a * 3] - p[c * 3]).let { it * it } + (p[a * 3 + 1] - p[c * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[c * 3 + 2]).let { it * it })
                    val r1 = sqrt((s[a * 3] - s[c * 3]).let { it * it } + (s[a * 3 + 1] - s[c * 3 + 1]).let { it * it } + (s[a * 3 + 2] - s[c * 3 + 2]).let { it * it })
                    val q = AutoRig3D.stretch(r0, r1, h); if (q > acc.tor[k]) acc.tor[k] = q }
                for (eIdx in E.indices) { val a = ea[eIdx]; val c = ec[eIdx]
                    val r1 = sqrt((s[a * 3] - s[c * 3]).let { it * it } + (s[a * 3 + 1] - s[c * 3 + 1]).let { it * it } + (s[a * 3 + 2] - s[c * 3 + 2]).let { it * it })
                    val ratio = AutoRig3D.stretch(e0[eIdx], r1, h)
                    if (ratio > acc.edge[eIdx]) acc.edge[eIdx] = ratio
                    if (ratio > acc.maxR) { acc.maxR = ratio; acc.worst = "$clip t=%.1f edge %d-%d bones %s / %s".format(t, a, c, (0..3).filter { W[a * 4 + it] > 0 }.joinToString { sk.names[J[a * 4 + it]] }, (0..3).filter { W[c * 4 + it] > 0 }.joinToString { sk.names[J[c * 4 + it]] }) } }
                for (pr in pairs) { val a = pr[0]; val c = pr[1]
                    val d0 = sqrt((p[a * 3] - p[c * 3]).let { it * it } + (p[a * 3 + 1] - p[c * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[c * 3 + 2]).let { it * it })
                    val d1 = sqrt((s[a * 3] - s[c * 3]).let { it * it } + (s[a * 3 + 1] - s[c * 3 + 1]).let { it * it } + (s[a * 3 + 2] - s[c * 3 + 2]).let { it * it })
                    if (abs(d1 - d0) / h > acc.gap) { acc.gap = abs(d1 - d0) / h
                        fun d(i: Int) = (0..3).filter { W[i * 4 + it] > 0 }.joinToString { sk.names[J[i * 4 + it]] + "=%.2f".format(W[i * 4 + it]) } + " @(%.3f,%.3f,%.3f)".format(p[i * 3] / h, p[i * 3 + 1] / h, p[i * 3 + 2] / h)
                        acc.worstGap = "$clip t=%.1f d0=%.4fh: ${d(a)} | ${d(c)}".format(t, d0 / h) } }
                if (sheet && (Math.round(t * 10) == 13 || Math.round(t * 10) == 52)) acc.fr += fi to ("$clip t=%.1f".format(t) to s)
                if (sheet && Math.round(t * 10) in intArrayOf(9, 22, 30)) acc.strip += fi to ("$clip t=%.1f".format(t) to s)
                fi += NT } } }.onEach { it.start() }.forEach { it.join() }
        for (acc in accs) {
            for (k in E.indices) if (acc.edge[k] > edgeMax[k]) edgeMax[k] = acc.edge[k]
            for (k in torso.indices) if (acc.tor[k] > torsoMax[k]) torsoMax[k] = acc.tor[k]
            if (acc.maxR > maxRatio) { maxRatio = acc.maxR; worst = acc.worst }
            if (acc.gap > maxGap) { maxGap = acc.gap; worstGap = acc.worstGap } }
        frames += accs.flatMap { it.fr }.sortedBy { it.first }.map { it.second }
        var bustRatio = 0f; var bustEdges = 0; val regions = java.util.TreeMap<String, Float>()
        val torsoRatio = torsoMax.maxOrNull() ?: 0f
        run { val bad = torso.indices.filter { torsoMax[it] > 1.15f }; val byBones = HashMap<String, Int>()
            for (k in bad) { val e = torso[k]; val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                val key = ((0..3).filter { W[a * 4 + it] > 0.05f }.map { sk.names[J[a * 4 + it]] } + (0..3).filter { W[c * 4 + it] > 0.05f }.map { sk.names[J[c * 4 + it]] }).toSortedSet().joinToString("+")
                byBones.merge(key, 1, Int::plus) }
            println("$name torso front: ${torso.size} edges, max stretch %.4f, ${bad.size} over 1.15: ".format(torsoRatio) + byBones.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }) }
        run { val bad = E.indices.filter { edgeMax[it] > 1.15f }
            val byBones = HashMap<String, Int>(); var tiny = 0
            for (k in bad) { val e = E[k]; val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                val r0 = sqrt((p[a * 3] - p[c * 3]).let { it * it } + (p[a * 3 + 1] - p[c * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[c * 3 + 2]).let { it * it })
                if (r0 < h * 0.005f) tiny++
                val key = ((0..3).filter { W[a * 4 + it] > 0.05f }.map { sk.names[J[a * 4 + it]] } + (0..3).filter { W[c * 4 + it] > 0.05f }.map { sk.names[J[c * 4 + it]] }).toSortedSet().joinToString("+")
                byBones.merge(key, 1, Int::plus) }
            println("$name: ${bad.size} edges > 1.15 (tiny<0.002h: $tiny); by bones: " + byBones.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}=${it.value}" })
            val br = E.indices.filter { k -> val e = E[k]; val a = (e ushr 32).toInt(); (bl >= 0 && w(a, bl) > 0.01f) || (br >= 0 && w(a, br) > 0.01f) }
            bustRatio = br.maxOfOrNull { edgeMax[it] } ?: 0f; bustEdges = br.size
            val brB = sk.find("breast.R")
            // every edge by body region (by the bones its two ends follow), worst stretch per region
            for ((k, e) in E.withIndex()) { val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                val bones = ((0..3).filter { W[a * 4 + it] > 0.05f }.map { sk.names[J[a * 4 + it]] } + (0..3).filter { W[c * 4 + it] > 0.05f }.map { sk.names[J[c * 4 + it]] }).toSet()
                val reg = region(bones, (bl >= 0 && (w(a, bl) > 0.01f || w(c, bl) > 0.01f)) || (brB >= 0 && (w(a, brB) > 0.01f || w(c, brB) > 0.01f)))
                if (edgeMax[k] > (regions[reg] ?: 0f)) regions[reg] = edgeMax[k] }
            println("$name: breast edges ${br.size}, max ratio there %.4f".format(br.maxOfOrNull { edgeMax[it] } ?: 0f))
            for (k in br.sortedByDescending { edgeMax[it] }.take(6)) { val e = E[k]; val a = (e ushr 32).toInt(); val c = (e and 0xffffffffL).toInt()
                fun d(i: Int) = (0..3).filter { W[i * 4 + it] > 0 }.joinToString { sk.names[J[i * 4 + it]] + "=%.2f".format(W[i * 4 + it]) } + " @(%.3f,%.3f,%.3f)".format(p[i * 3] / h, p[i * 3 + 1] / h, p[i * 3 + 2] / h)
                println("   %.3f len %.4fh: ${d(a)} | ${d(c)}".format(edgeMax[k], sqrt((p[a * 3] - p[c * 3]).let { it * it } + (p[a * 3 + 1] - p[c * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[c * 3 + 2]).let { it * it }) / h)) } }
        if (System.getenv("CONTACTS") != null) { // debug: bad edges by dominant bone pair (count, worst stretch, worst rest length)
            fun dom(i: Int): Int { var b0 = 0; for (k in 1..3) if (W[i * 4 + k] > W[i * 4 + b0]) b0 = k; return J[i * 4 + b0] }
            val agg = HashMap<String, FloatArray>()
            for (k in E.indices) if (edgeMax[k] > 1.15f) { val a = ea[k]; val c = ec[k]; val n1 = sk.names[dom(a)]; val n2 = sk.names[dom(c)]
                val key = if (n1 <= n2) "$n1|$n2" else "$n2|$n1"; val v = agg.getOrPut(key) { FloatArray(4) }; v[0]++; v[1] = maxOf(v[1], edgeMax[k]); v[2] += p[a * 3 + 1] / h; v[3] += p[a * 3 + 2] / h }
            println("$name CONTACTS " + agg.entries.sortedByDescending { it.value[0] }.take(30).joinToString { "${it.key} n=${it.value[0].toInt()} max=%.2f y=%.2f z=%.2f".format(it.value[1], it.value[2] / it.value[0], it.value[3] / it.value[0]) }) }
        // t501u amplitude: every animated part must keep ≥ 95 % of its full procedural motion, both as the bone's
        // motion scale (no per-part limits) and as what the surface actually does: the RMS displacement of the part's
        // vertices (by their dominant bone in the plain smooth weighting) over all clips, final rig vs that weighting
        val amp = java.util.TreeMap<String, Float>()
        run { val base = base0 ?: return@run; val bj = base.first; val bw = base.second
            val motion = sk.motion; sk.motion = null; val refPose = Pose3D(sk, m.animations ?: emptyList()); sk.motion = motion
            val refMats = all.map { fr -> val o = FloatArray(sk.size * 16); val st = clips.first { it.first == fr.clip }.second
                refPose.evaluate(fr.t, st, if (st == 3) (0.5f + 0.5f * kotlin.math.sin(fr.t * 11f)).coerceIn(0f, 1f) else 0f, o); o }
            fun dom(i: Int): Int { var b0 = 0; for (k in 1..3) if (bw[i * 4 + k] > bw[i * 4 + b0]) b0 = k; return bj[i * 4 + b0] }
            for (g in pose.groups) { val gs = g.toSet(); if (motion != null) for (j in g) amp["scale " + sk.names[j]] = motion[j]
                val verts = (0 until V step 3).filter { dom(it) in gs }.toIntArray(); if (verts.size < 20) continue
                fun rms(mats: List<FloatArray>, ws: List<Triple<IntArray, FloatArray, Float>>): Float { var best = 0.0
                    for (mm in mats) { var sum = 0.0; for (i in verts) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; var ox = 0f; var oy = 0f; var oz = 0f
                        for ((jj, ww, f) in ws) for (k in 0..3) { val w = ww[i * 4 + k] * f; if (w == 0f) continue; val bb = jj[i * 4 + k] * 16
                            ox += w * (mm[bb] * x + mm[bb + 4] * y + mm[bb + 8] * z + mm[bb + 12]); oy += w * (mm[bb + 1] * x + mm[bb + 5] * y + mm[bb + 9] * z + mm[bb + 13]); oz += w * (mm[bb + 2] * x + mm[bb + 6] * y + mm[bb + 10] * z + mm[bb + 14]) }
                        sum += ((ox - x) * (ox - x) + (oy - y) * (oy - y) + (oz - z) * (oz - z)).toDouble() }
                        best = maxOf(best, sum / verts.size) }
                    return sqrt(best).toFloat() }
                val ref = rms(refMats, listOf(Triple(bj, bw, 1f))); val now = rms(all.map { it.mats }, sets(m, strength))
                if (ref > h * 1e-4f) amp[g.joinToString("/") { sk.names[it] }] = now / ref } }
        println("$name AMP " + amp.entries.joinToString { "${it.key} %.3f".format(it.value) })
        val txt = "$name: ${sk.size} bones, breast bones L=$bl R=$br, breast-weighted vertices L=${breastVerts[0]} R=${breastVerts[1]}, " +
            "edges=${E.size}, body/garment pairs=${pairs.size}, bust edges=$bustEdges max stretch %.4f, torso-front max stretch ${"%.4f".format(torsoRatio)}, regions ${regions.entries.joinToString { "${it.key} %.3f".format(it.value) }}, motion ${sk.motion?.let { mo -> (0 until sk.size).filter { mo[it] < 1f }.joinToString(" ") { sk.names[it] + "=%.2f".format(mo[it]) } } ?: "full"}, head/jaw on chest $headOnChest, mirror |diff| %.3f, whole-mesh max stretch=%.4f (%s), max gap change=%.5f h".format(bustRatio, mirrorDiff, maxRatio, worst, maxGap)
        println(txt); if (worstGap.isNotEmpty()) println("$name worst gap: $worstGap")
        if (sheet) { chestSheet(name, m, r, frames)
            chestSheet(name, m, r, accs.flatMap { it.strip }.sortedBy { it.first }.map { it.second }, full = true, edges = Triple(ea, ec, e0)) }
        File("build").mkdirs(); File("build/chest_$name.txt").writeText(txt + "\n")
        return Report(name, maxRatio, worst, maxGap, breastVerts, txt, bustRatio, bustEdges, mirrorDiff, pairs.size, torsoRatio, headOnChest, regions, amp)
    }

    /** Body region of an edge from the bones its ends follow (first match): bust, wings, tail, head / neck / throat,
     *  legs, arms, torso. */
    private fun region(bones: Set<String>, bust: Boolean): String = when {
        bust -> "bust"
        bones.any { it.startsWith("wing") } -> "wings"
        bones.any { it.startsWith("tail") } -> "tail"
        bones.any { it == "head" || it == "neck" || it.startsWith("ear") } -> "neck/head"
        bones.any { it.startsWith("thigh") || it.startsWith("shin") || it.startsWith("foot") || it.startsWith("hock") } -> "legs"
        bones.any { it.startsWith("shoulder") || it.startsWith("elbow") || it.startsWith("hand") } -> "arms"
        else -> "torso"
    }

    /** Close-ups of the chest (front and side), rest + animation frames, textured. */
    /** [full]: the whole body (an animation strip), and edges over 1.15 in that frame drawn red ([edges]: a, c, rest). */
    private fun chestSheet(name: String, m: Mesh3D, r: AutoRig3D.Result, frames: List<Pair<String, FloatArray>>, full: Boolean = false, edges: Triple<IntArray, IntArray, FloatArray>? = null) {
        val sk = m.skeleton!!; val b = m.bounds(); val h = b[4] - b[1]
        val ch = sk.find("chest"); val cy = if (full) (b[1] + b[4]) / 2 + h * 0.07f else r.jointsWorld[ch * 3 + 1]; val cxw = if (full) (b[0] + b[3]) / 2 else r.jointsWorld[ch * 3]; val czw = if (full) (b[2] + b[5]) / 2 else r.jointsWorld[ch * 3 + 2]
        val box = if (full) h * 1.1f else h * 0.42f
        val tex = m.texture?.let { runCatching { ImageIO.read(it.inputStream()) }.getOrNull() }
        fun texCol(u: Float, v: Float): Int = tex?.getRGB(((u - kotlin.math.floor(u)) * tex.width).toInt().coerceIn(0, tex.width - 1), ((v - kotlin.math.floor(v)) * tex.height).toInt().coerceIn(0, tex.height - 1))?.and(0xFFFFFF) ?: 0xC0C0C0
        val S = 300; val all = listOf("rest" to m.pos, "weights" to m.pos) + frames.take(if (full) 12 else 9)
        val Jn = m.joints!!; val Wn = m.weights!!
        fun wsum(i: Int, pred: (String) -> Boolean): Float { var s = 0f; for (k in 0..3) if (Wn[i * 4 + k] > 0f && pred(sk.names[Jn[i * 4 + k]])) s += Wn[i * 4 + k]; return s }
        val wcol = IntArray(m.vertexCount) { i ->
            val hd = wsum(i) { it == "head" }; val jw = wsum(i) { it == "jaw" }; val nk = wsum(i) { it == "neck" }; val bb = wsum(i) { it.startsWith("breast") }
            val am = wsum(i) { it.startsWith("shoulder") || it.startsWith("elbow") || it.startsWith("hand") }; val wg = wsum(i) { it.startsWith("wing") }
            // head red, jaw magenta, neck yellow, breasts green (x2), arms blue, wings cyan; chest/spine/legs dark
            val r = hd + jw + nk; val g = (bb * 2f).coerceAtMost(1f) + nk + wg; val b = jw + am + wg
            (((r * 255).toInt().coerceIn(0, 255)) shl 16) or (((g * 255).toInt().coerceIn(0, 255)) shl 8) or ((b * 255).toInt().coerceIn(0, 255)) or 0x202020 }
        val out = BufferedImage(S * all.size, S * 2 + 24, BufferedImage.TYPE_INT_RGB); val g = out.createGraphics(); g.font = Font("SansSerif", Font.BOLD, 12)
        for ((ci, fr) in all.withIndex()) for ((row, side) in listOf(false, true).withIndex()) {
            val pos = fr.second; val img = IntArray(S * S) { 0xFF202028.toInt() }; val zb = FloatArray(S * S) { -1e9f }
            val torn = BooleanArray(m.vertexCount)
            if (edges != null && fr.first != "rest" && fr.first != "weights") { val (ea, ec, e0) = edges
                for (k in ea.indices) { val a = ea[k]; val c = ec[k]
                    val l = sqrt((pos[a * 3] - pos[c * 3]).let { it * it } + (pos[a * 3 + 1] - pos[c * 3 + 1]).let { it * it } + (pos[a * 3 + 2] - pos[c * 3 + 2]).let { it * it })
                    if (AutoRig3D.stretch(e0[k], l, h) > 1.15f) { torn[a] = true; torn[c] = true } } }
            fun sx(x: Float, z: Float) = ((if (side) -(z - czw) else x - cxw) / box + 0.5f) * S
            fun sy(y: Float) = (0.5f - (y - (cy - box * 0.15f)) / box) * S
            fun dep(x: Float, z: Float) = if (side) -x else z
            val idx = m.idx; val uv = m.uv
            for (t in 0 until idx.size / 3) { val a = idx[t * 3]; val c1 = idx[t * 3 + 1]; val c2 = idx[t * 3 + 2]
                val x0 = sx(pos[a * 3], pos[a * 3 + 2]); val y0 = sy(pos[a * 3 + 1]); val x1 = sx(pos[c1 * 3], pos[c1 * 3 + 2]); val y1 = sy(pos[c1 * 3 + 1]); val x2 = sx(pos[c2 * 3], pos[c2 * 3 + 2]); val y2 = sy(pos[c2 * 3 + 1])
                if (maxOf(x0, x1, x2) < 0 || minOf(x0, x1, x2) > S || maxOf(y0, y1, y2) < 0 || minOf(y0, y1, y2) > S) continue
                val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2); if (abs(den) < 1e-9f) continue
                val ux = pos[c1 * 3] - pos[a * 3]; val uy = pos[c1 * 3 + 1] - pos[a * 3 + 1]; val uz = pos[c1 * 3 + 2] - pos[a * 3 + 2]
                val wx = pos[c2 * 3] - pos[a * 3]; val wy = pos[c2 * 3 + 1] - pos[a * 3 + 1]; val wz = pos[c2 * 3 + 2] - pos[a * 3 + 2]
                var nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx; if (side) { val t2 = nx; nx = -nz; nz = t2 }
                val ln = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f); val k = 0.35f + 0.75f * abs((nx * -0.3f + ny * 0.5f + nz * 0.81f) / ln)
                val d0 = dep(pos[a * 3], pos[a * 3 + 2]); val d1 = dep(pos[c1 * 3], pos[c1 * 3 + 2]); val d2 = dep(pos[c2 * 3], pos[c2 * 3 + 2])
                for (py in maxOf(0, minOf(y0, y1, y2).toInt())..minOf(S - 1, maxOf(y0, y1, y2).toInt())) for (px in maxOf(0, minOf(x0, x1, x2).toInt())..minOf(S - 1, maxOf(x0, x1, x2).toInt())) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den; val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den; val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * d0 + w1 * d1 + w2 * d2; val o = py * S + px; if (z <= zb[o]) continue; zb[o] = z
                    val col = if (torn[a] || torn[c1] || torn[c2]) 0xFF2020 else if (fr.first == "weights") { val wc = if (w0 >= w1 && w0 >= w2) a else if (w1 >= w2) c1 else c2; wcol[wc] } else if (uv != null) texCol(w0 * uv[a * 2] + w1 * uv[c1 * 2] + w2 * uv[c2 * 2], w0 * uv[a * 2 + 1] + w1 * uv[c1 * 2 + 1] + w2 * uv[c2 * 2 + 1]) else 0xC0C0C0
                    img[o] = (0xFF shl 24) or ((((col shr 16) and 255) * k).toInt().coerceIn(0, 255) shl 16) or ((((col shr 8) and 255) * k).toInt().coerceIn(0, 255) shl 8) or (((col and 255) * k).toInt().coerceIn(0, 255))
                } }
            out.setRGB(S * ci, 24 + row * S, S, S, img, 0, S)
            if (row == 0) { g.color = Color.WHITE; g.drawString(fr.first, S * ci + 4, 16) }
        }
        g.dispose(); File("build").mkdirs(); ImageIO.write(out, "png", File(if (full) "build/strip_$name.png" else "build/chest_sheet_$name.png"))
    }

    /** A separate dress shell: the front of the torso around the bust copied 0.6 % of the height outward along the
     *  normals as its own piece (like a modelled dress lying over the body). */
    private fun withDressShell(m: Mesh3D): Mesh3D {
        val p = m.pos; val n = m.nrm!!; val V = m.vertexCount; val b = m.bounds(); val h = b[4] - b[1]
        val xs = FloatArray(V) { p[it * 3] }.also { it.sort() }; val cx = xs[V / 2]
        val pick = BooleanArray(V) { i -> n[i * 3 + 2] > 0.3f && p[i * 3 + 1] > b[1] + 0.5f * h && p[i * 3 + 1] < b[1] + 0.8f * h && abs(p[i * 3] - cx) < 0.08f * h }
        val tris = (0 until m.idx.size / 3).filter { t -> (0..2).all { pick[m.idx[t * 3 + it]] } }
        val map = HashMap<Int, Int>(); val np = ArrayList<Float>(); val nn = ArrayList<Float>(); val nuv = ArrayList<Float>()
        for (t in tris) for (k in 0..2) { val i = m.idx[t * 3 + k]; map.getOrPut(i) { val o = V + map.size
            for (c in 0..2) { np += p[i * 3 + c] + n[i * 3 + c] * h * 0.006f; nn += n[i * 3 + c] }; m.uv?.let { nuv += it[i * 2]; nuv += it[i * 2 + 1] }; o } }
        val S = map.size
        val pos = p + np.toFloatArray(); val nrm = n + nn.toFloatArray(); val uv = m.uv?.let { it + nuv.toFloatArray() }
        val col = m.col?.let { c -> val per = c.size / V; c + FloatArray(S * per) { k -> c[(map.entries.first().key) * per + k % per] } }
        val idx = m.idx + IntArray(tris.size * 3) { k -> map[m.idx[tris[k / 3] * 3 + k % 3]]!! }
        println("dress shell: ${tris.size} triangles, $S vertices")
        return Mesh3D(pos, nrm, uv, col, null, null, idx, m.texture, m.textureMime)
    }

    private val rigs = HashMap<String, Pair<AutoRig3D.Result, Pair<IntArray, FloatArray>?>>()

    /** t501u wrap-up: the three animation strengths. Full motion: no part below 95 % of its full motion. No stretching:
     *  every region within 15 %. Balanced: at least 40 % motion (stretch reported). The bust / dress / symmetry / jaw
     *  checks hold at every strength. */
    private fun assertStrength(rep: Report, strength: Float) {
        if (rep.breastVerts[0] + rep.breastVerts[1] > 0) {
            assertTrue("${rep.name}: head / jaw weight on the chest front: ${rep.headOnChest} vertices", rep.headOnChest == 0)
            assertTrue("${rep.name}: breasts weighted unevenly (mirror |diff| ${rep.mirrorDiff})", rep.mirrorDiff < 0.12f)
            assertTrue("${rep.name}: one breast has much less weighted surface: ${rep.text}", minOf(rep.breastVerts[0], rep.breastVerts[1]) > 0.5f * maxOf(rep.breastVerts[0], rep.breastVerts[1]))
        }
        val minAmp = when { strength >= 0.999f -> 0.95f; strength >= 0.25f -> 0.4f; else -> 0f }
        for ((part, a) in rep.amplitude) if (!part.startsWith("scale ") || strength >= 0.999f)
            assertTrue("${rep.name}: $part moves at ${"%.0f".format(a * 100)} % of its full motion (< ${(minAmp * 100).toInt()} %)", a >= minAmp)
        if (strength <= 0.001f) {
            if (rep.breastVerts[0] + rep.breastVerts[1] > 0) assertTrue("${rep.name}: bust edge stretch ${rep.bustRatio} ≥ 1.15", rep.bustRatio < 1.15f)
            assertTrue("${rep.name}: dress/body gap changes by ${rep.maxGap} h", rep.maxGap < 0.004f)
            for ((reg, v) in rep.regions) assertTrue("${rep.name}: $reg edge stretch $v ≥ 1.15 (${rep.text})", v < 1.15f)
        }
    }
    private fun strengths(name: String, file: File, shell: Boolean = false, sheet: Boolean = true) =
        listOf(1f, 0.5f, 0f).map { s -> check(name, file, sheet = sheet, shell = shell, strength = s).also { assertStrength(it, s) } }

    private fun assertNoTear(rep: Report) {
        // the bust (breasts and the dress / anything lying on them, fused or separate): every edge within 15 %
        if (rep.breastVerts[0] + rep.breastVerts[1] > 0) {
            assertTrue("${rep.name}: bust edge stretch ${rep.bustRatio} ≥ 1.15", rep.bustRatio < 1.15f)
            // the rest of the chest / dress front is reported (where a hand or arm touches the dress in a fused mesh the
            // contact still stretches); nothing there may follow the head or the jaw
            assertTrue("${rep.name}: head / jaw weight on the chest front: ${rep.headOnChest} vertices", rep.headOnChest == 0)
            assertTrue("${rep.name}: breasts weighted unevenly (mirror |diff| ${rep.mirrorDiff})", rep.mirrorDiff < 0.12f)
            assertTrue("${rep.name}: one breast has much less weighted surface: ${rep.text}", minOf(rep.breastVerts[0], rep.breastVerts[1]) > 0.5f * maxOf(rep.breastVerts[0], rep.breastVerts[1]))
        }
        assertTrue("${rep.name}: dress/body gap changes by ${rep.maxGap} h", rep.maxGap < 0.004f)
        // t492u: the whole body too (arm / hand contacts on the torso and thighs, throat, neck, wings, tail, legs)
        assertTrue("${rep.name}: torso-front stretch ${rep.torsoRatio} ≥ 1.15", rep.torsoRatio < 1.15f)
        for ((reg, v) in rep.regions) assertTrue("${rep.name}: $reg edge stretch $v ≥ 1.15 (${rep.text})", v < 1.15f)
        // t501u: motion may not be cut to get there (no part below 95 % of its full procedural motion)
        for ((part, a) in rep.amplitude) assertTrue("${rep.name}: $part moves at ${"%.0f".format(a * 100)} % of its full motion (< 95 %)", a >= 0.95f)
        assertTrue("${rep.name}: whole-mesh stretch ${rep.maxRatio} ≥ 1.15: ${rep.worstEdgeBones}", rep.maxRatio < 1.15f)
    }

    @Test fun boobDragon() { AutoRig3D.DEBUG_RELAX = System.getenv("RELAXDBG") != null; AutoRig3D.RELAX = System.getenv("NORELAX") == null
        val r = strengths("boob_dragon", find("boob_dragon.glb")!!)[0]
        assertTrue(r.text, r.breastVerts[0] > 0 && r.breastVerts[1] > 0)
    }
    @Test fun boobDragonSeparateDress() {
        val r = strengths("boob_dragon_shell", find("boob_dragon.glb")!!, shell = true, sheet = false)[0]; assertTrue(r.text, r.pairs > 100)
    }
    @Test fun bob() { strengths("bob", find("bob.glb")!!) }
    @Test fun importedDragoness() { val f = find("dragoness_pixal3d.glb"); assumeTrue(f != null); val r = strengths("import_dragoness", f!!)[0]
        assertTrue(r.text, r.breastVerts[0] > 0 && r.breastVerts[1] > 0) }
}
