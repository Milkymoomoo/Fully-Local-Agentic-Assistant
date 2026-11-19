package app.belle.assistant.mesh

/**
 * Decides whether a model can be drawn with back-face culling.
 *
 * Generated models (Pixal3D/TRELLIS marching cubes) are closed, consistently wound surfaces, and clothing is a
 * thick shell whose INNER surface faces away from the viewer. At full detail the outer surface hides it, but a
 * progressive-mesh level moves the outer surface a little and the inner shell pokes through as dark/grey smears
 * (seen through it from the back). For a closed, consistently oriented mesh those pokes are exactly the
 * back faces, so culling them is lossless at full detail and fixes every reduced level. Open or inconsistently
 * wound meshes (single-sided cloth, wings, hand-made exports) stay double-sided.
 *
 * Returns +1 = counter-clockwise faces point outward (cull back faces), −1 = clockwise outward (cull front faces in
 * GL terms), 0 = keep both sides.
 */
object Orientation {
    class Report(val mode: Int, val manifoldConsistent: Float, val volume: Double)

    fun analyse(m: Mesh3D): Report {
        // weld vertices by position (UV/normal seams split them in the file)
        val b = m.bounds(); val s = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).coerceAtLeast(1e-9f) / 1_000_000.0
        val ids = HashMap<Long, Int>(m.vertexCount * 2); val weld = IntArray(m.vertexCount)
        for (i in 0 until m.vertexCount) {
            val x = Math.round(m.pos[i * 3] / s); val y = Math.round(m.pos[i * 3 + 1] / s); val z = Math.round(m.pos[i * 3 + 2] / s)
            val key = (x * 73856093L) xor (y * 19349663L) xor (z * 83492791L)
            weld[i] = ids.getOrPut(key) { i }
        }
        // directed edge counts
        val dir = HashMap<Long, Int>(m.idx.size * 2)
        fun key(a: Int, c: Int) = (a.toLong() shl 32) or (c.toLong() and 0xffffffffL)
        var vol = 0.0
        for (t in 0 until m.triCount) {
            val a = weld[m.idx[t * 3]]; val bb = weld[m.idx[t * 3 + 1]]; val c = weld[m.idx[t * 3 + 2]]
            if (a == bb || bb == c || a == c) continue
            for ((p, q) in listOf(a to bb, bb to c, c to a)) dir.merge(key(p, q), 1, Int::plus)
            val p = m.pos; val i = m.idx[t * 3] * 3; val j = m.idx[t * 3 + 1] * 3; val k = m.idx[t * 3 + 2] * 3
            vol += (p[i].toDouble() * (p[j + 1] * p[k + 2] - p[j + 2] * p[k + 1]) - p[i + 1].toDouble() * (p[j] * p[k + 2] - p[j + 2] * p[k]) + p[i + 2].toDouble() * (p[j] * p[k + 1] - p[j + 1] * p[k])) / 6.0
        }
        var good = 0; var total = 0
        for ((k, n) in dir) { val a = (k ushr 32).toInt(); val c = k.toInt(); val back = dir[key(c, a)] ?: 0
            total++; if (n == 1 && back == 1) good++ }
        val ratio = if (total == 0) 0f else good.toFloat() / total
        val mode = if (ratio < 0.97f || kotlin.math.abs(vol) < 1e-12) 0 else if (vol > 0) 1 else -1
        return Report(mode, ratio, vol)
    }
}
