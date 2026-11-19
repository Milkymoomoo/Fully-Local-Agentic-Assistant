package app.belle.assistant.mesh

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automatic rig for a 3D avatar that came without a skeleton (glTF convention: +Y up, facing +Z).
 * Finds the neck as the narrowest slice in the upper body, the head above it, the shoulders/arms as the
 * outer parts below the neck, and a mouth on the front of the lower head. Two styles:
 *   • "skinned": every vertex blends its two nearest bones (smooth bends)
 *   • "rigid":   every vertex follows exactly one bone (N64-style rigid segments)
 * Jaw vertices (front of the head, below the mouth line) get their own bone for lip-sync.
 */
object AutoRig3D {
    /** 2: whole lower jaw on a hinge (muzzle-aware). 3: chest/breast bones (the whole bust follows the chest, never
     *  the arms), weight transfer for clothing and loose parts from the body underneath + weight smoothing, ears,
     *  digitigrade legs (knee forward, hock back), a 5-bone tail. Avatars rigged by an older version are re-rigged once.
     *  4: symmetric whole-breast weighting with a smooth falloff that includes anything over the breast (dress, top,
     *  fused or separate), capped breast bounce, and a no-tear pass: every edge is checked against the procedural
     *  animation clips and weights are relaxed until no edge stretches or shrinks more than 10 %.
     *  5: the whole body: a contact pass (two parts that touch fade to the bone they share, along the surface) and
     *  per-part motion limits saved with the skeleton, so no edge anywhere (neck, throat, arms on the body, wings, tail,
     *  legs) stretches more than ~12 % in any procedural clip. */
    /*  6: full procedural motion again (no per-part motion limits): joint seams blend wider, the head turn is shared
     *  by the neck, the contact pass gives a no-stretch weighting and a motion budget gives every part back ≥ 95 % of its
     *  motion. Both weightings are saved (JOINTS_0/WEIGHTS_0 full motion, JOINTS_1/WEIGHTS_1 no stretching) and the
     *  renderer mixes them live by the avatar's animation strength (Full motion / Balanced / No stretching). */
    const val VERSION = 6
    val NAMES = arrayOf("root", "spine", "chest", "neck", "head", "jaw", "shoulder.L", "elbow.L", "hand.L", "shoulder.R", "elbow.R", "hand.R")
    val PARENT = intArrayOf(-1, 0, 1, 2, 3, 4, 2, 6, 7, 2, 9, 10)
    const val HEAD = 4; const val JAW = 5; const val NECK = 3; const val CHEST = 2; const val SPINE = 1

    class Result(val mesh: Mesh3D, val jointsWorld: FloatArray, val mouth: FloatArray, val jawVertices: Int, val jaw: Jaw? = null) {
        val boneNames: List<String> get() = mesh.skeleton?.names?.toList() ?: emptyList()
    }

    fun rig(m: Mesh3D, style: String = "skinned"): Result {
        val V = m.vertexCount; val p = m.pos; val b = m.bounds()
        val minY = b[1]; val maxY = b[4]; val h = (maxY - minY).coerceAtLeast(1e-6f); val cx: Float; val cz: Float
        // centre line = median, not the bounding box (a tail or a wing would drag the box centre sideways)
        run { val xs = FloatArray((V + 3) / 4) { p[it * 4 * 3] }; val zs = FloatArray(xs.size) { p[it * 4 * 3 + 2] }; xs.sort(); zs.sort()
            cx = xs[xs.size / 2]; cz = zs[zs.size / 2] }
        // width profile over 48 slices (central 80 % of points per slice to ignore stray bits)
        val S = 48; val minX = FloatArray(S) { Float.MAX_VALUE }; val maxX = FloatArray(S) { -Float.MAX_VALUE }; val maxZ = FloatArray(S) { -Float.MAX_VALUE }
        for (i in 0 until V) { val s = (((p[i * 3 + 1] - minY) / h) * (S - 1)).toInt().coerceIn(0, S - 1)
            minX[s] = minOf(minX[s], p[i * 3]); maxX[s] = maxOf(maxX[s], p[i * 3]); maxZ[s] = maxOf(maxZ[s], p[i * 3 + 2]) }
        val width = FloatArray(S) { if (maxX[it] >= minX[it]) maxX[it] - minX[it] else 0f }
        // "core" width per slice: the connected run of geometry around the middle line (ignores horns, wings, arms
        // that are separated from the body by a gap)
        val core = FloatArray(S)
        run { val xs = Array(S) { ArrayList<Float>() }
            for (i in 0 until V step 2) { val s = (((p[i * 3 + 1] - minY) / h) * (S - 1)).toInt().coerceIn(0, S - 1); xs[s] += p[i * 3] }
            val gapMax = h * 0.02f
            for (s in 0 until S) { val l = xs[s]; if (l.isEmpty()) continue; l.sort()
                var k = l.indices.minBy { abs(l[it] - cx) }; var lo = k; var hi = k
                while (lo > 0 && l[lo] - l[lo - 1] < gapMax) lo--; while (hi < l.size - 1 && l[hi + 1] - l[hi] < gapMax) hi++
                core[s] = l[hi] - l[lo] } }
        // head = widest core slice in the top part (70–95 %), neck = narrowest core slice below it (down to 40 %)
        var headS = (S * 0.85f).toInt(); for (s in (S * 0.7f).toInt() until (S * 0.95f).toInt()) if (core[s] > core[headS]) headS = s
        // a tall figure (standing, full body) uses the core-width neck; a bust uses the plain silhouette width
        val fullBody = h > (b[3] - b[0]) * 1.3f
        var neckS = (S * 0.7f).toInt(); var best = Float.MAX_VALUE
        if (fullBody) { for (s in (S * 0.55f).toInt() until headS) if (core[s] > 0 && core[s] < best) { best = core[s]; neckS = s } }
        else for (s in (S * 0.4f).toInt() until (S * 0.92f).toInt()) if (width[s] > 0 && width[s] < best) { best = width[s]; neckS = s }
        if (best == Float.MAX_VALUE) { best = width[neckS] }
        val neckY = minY + h * neckS / (S - 1); val headTop = maxY
        val headC = floatArrayOf(cx, (neckY + headTop) / 2, cz)
        // head centre x/z: mean of vertices above the neck
        run { var sx = 0.0; var sz = 0.0; var n = 0; for (i in 0 until V) if (p[i * 3 + 1] > neckY) { sx += p[i * 3]; sz += p[i * 3 + 2]; n++ }
            if (n > 0) { headC[0] = (sx / n).toFloat(); headC[2] = (sz / n).toFloat() } }
        val headR = maxOf((headTop - neckY) / 2, best / 2)
        // shoulders: widest slice in the band just below the neck
        var shS = neckS - 1; var wmax = 0f
        val shW = if (fullBody) core else width   // wings beside the shoulders are not the shoulders
        for (s in maxOf(0, neckS - (if (fullBody) S / 10 else S / 6)) until neckS) if (shW[s] > wmax) { wmax = shW[s]; shS = s }
        val shY = minY + h * shS / (S - 1); val torsoHalf = maxOf(best * 0.9f, wmax * 0.28f)
        val chest = floatArrayOf(cx, shY, cz); val neck = floatArrayOf(headC[0], neckY, headC[2])
        val spine = floatArrayOf(cx, minY + (shY - minY) * 0.5f, cz); val root = floatArrayOf(cx, minY, cz)
        // ---- full-body extras (only added when found): legs, wings, tail ----
        val extraNames = ArrayList<String>(); val extraParent = ArrayList<Int>(); val extraW = ArrayList<FloatArray>()
        fun addBone(n: String, parent: Int, w: FloatArray): Int { extraNames += n; extraParent += parent; extraW += w; return NAMES.size + extraNames.size - 1 }
        val bodyDepth = h * 0.22f
        // legs: two separate clusters across x in the low slices, among vertices near the body's middle depth
        fun legGap(y0: Float, y1: Float): Float? {
            // (not the back half: a tail lying on the ground between the feet would close the gap)
            val xs = ArrayList<Float>(); for (i in 0 until V) { val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; if (y in y0..y1 && abs(z - cz) < bodyDepth && z > cz - h * 0.04f) xs += p[i * 3] }
            if (xs.size < 50) return null; xs.sort()
            var gap = 0f; var at = cx; for (k in 1 until xs.size) { val g = xs[k] - xs[k - 1]; val mid = (xs[k] + xs[k - 1]) / 2
                if (g > gap && abs(mid - cx) < h * 0.08f) { gap = g; at = mid } }
            return if (gap > h * 0.02f) at else null
        }
        var hipY = Float.NaN
        if (legGap(minY + h * 0.08f, minY + h * 0.14f) != null) {
            var y = minY + h * 0.14f; while (y < minY + h * 0.5f && legGap(y, y + h * 0.03f) != null) y += h * 0.02f
            hipY = y
        }
        // where the legs stop being separate (the hem of a skirt or dress, or the crotch)
        val legTopY = hipY
        // standing figure: the hip joints sit about half way from the feet to the neck. A skirt hides the real
        // hips, so never put them lower than that (the legs would bend at the hem)
        if (!hipY.isNaN() && fullBody) hipY = maxOf(hipY, minY + (neckY - minY) * 0.52f).coerceAtMost(shY - h * 0.1f)
        // per side: thigh, shin, (metatarsus for digitigrade legs), foot; -1 where absent
        val legBones = IntArray(8) { -1 }
        var digitigrade = false
        if (!hipY.isNaN()) {
            root[1] = hipY; spine[1] = hipY + (shY - hipY) * 0.5f
            for ((si, side) in listOf(0 to 1f, 1 to -1f)) {
                var fx = 0.0; var fy = 0.0; var fz = 0.0; var n = 0
                for (i in 0 until V) if ((p[i * 3] - cx) * side > 0 && p[i * 3 + 1] < minY + h * 0.04f && abs(p[i * 3 + 2] - cz) < bodyDepth) { fx += p[i * 3]; fy += p[i * 3 + 1]; fz += p[i * 3 + 2]; n++ }
                if (n == 0) continue
                val foot = floatArrayOf((fx / n).toFloat(), (fy / n).toFloat() + h * 0.02f, (fz / n).toFloat())
                val hip = floatArrayOf(cx + (foot[0] - cx) * 0.8f, hipY, cz)
                // side profile of this leg: mean depth (z) per height slice between the foot and the hip
                val L = 12; val zs = DoubleArray(L); val zn = IntArray(L); val legH = (hip[1] - foot[1]).coerceAtLeast(1e-4f)
                for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]
                    if ((x - cx) * side <= 0 || y < foot[1] || y > hip[1] || abs(p[i * 3 + 2] - cz) > bodyDepth * 1.5f) continue
                    val k = (((y - foot[1]) / legH) * L).toInt().coerceIn(0, L - 1); zs[k] += p[i * 3 + 2]; zn[k]++ }
                fun zAt(k: Int) = if (zn[k] > 0) (zs[k] / zn[k]).toFloat() else Float.NaN
                fun lineZ(k: Int) = foot[2] + (hip[2] - foot[2]) * (k + 0.5f) / L
                // hock: the most backward point in the lower part; knee: the most forward point in the upper part
                var hockK = -1; var hockDev = 0f; for (k in 1..(L * 45 / 100)) { val z = zAt(k); if (z.isNaN()) continue; val d = lineZ(k) - z; if (d > hockDev) { hockDev = d; hockK = k } }
                var kneeK = -1; var kneeDev = 0f; for (k in (L / 2) until L - 1) { val z = zAt(k); if (z.isNaN()) continue; val d = z - lineZ(k); if (d > kneeDev) { kneeDev = d; kneeK = k } }
                val digi = hockK > 0 && hockDev > legH * 0.06f && (kneeK < 0 || kneeDev > legH * 0.02f)
                val sfx = if (side > 0) "L" else "R"
                val o = si * 4
                if (digi) {
                    digitigrade = true
                    val hockY = foot[1] + legH * (hockK + 0.5f) / L
                    val hock = floatArrayOf(hip[0] + (foot[0] - hip[0]) * (1 - (hockY - foot[1]) / legH), hockY, zAt(hockK))
                    val kneeY = if (kneeK > 0) foot[1] + legH * (kneeK + 0.5f) / L else (hip[1] + hockY) / 2
                    val knee = floatArrayOf(hip[0] + (foot[0] - hip[0]) * (1 - (kneeY - foot[1]) / legH), kneeY, if (kneeK > 0) zAt(kneeK) else hip[2] + h * 0.03f)
                    legBones[o] = addBone("thigh.$sfx", 0, hip); legBones[o + 1] = addBone("shin.$sfx", legBones[o], knee)
                    legBones[o + 2] = addBone("hock.$sfx", legBones[o + 1], hock); legBones[o + 3] = addBone("foot.$sfx", legBones[o + 2], foot)
                } else {
                    val kz = if (kneeK > 0) zAt(kneeK) else (hip[2] + foot[2]) / 2 + h * 0.02f
                    val knee = floatArrayOf((hip[0] + foot[0]) / 2, (hip[1] + foot[1]) / 2, kz)
                    legBones[o] = addBone("thigh.$sfx", 0, hip); legBones[o + 1] = addBone("shin.$sfx", legBones[o], knee); legBones[o + 3] = addBone("foot.$sfx", legBones[o + 1], foot)
                }
            }
        }
        fun arm(side: Float): Triple<FloatArray, FloatArray, FloatArray> {
            val sh = floatArrayOf(cx + side * torsoHalf, shY - h * 0.02f, cz)
            // hand: average of the 2 % of this side's lower vertices farthest from the shoulder
            val cand = ArrayList<Int>()
            val armLow = if (legTopY.isNaN()) -Float.MAX_VALUE else legTopY + (shY - legTopY) * 0.3f   // standing figure: hands are above the hips
            for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; if ((x - cx) * side > torsoHalf * 0.6f && y < shY - h * 0.03f && y > armLow && p[i * 3 + 2] > cz - h * 0.06f) cand += i }
            if (cand.size < 8) return Triple(sh, floatArrayOf(sh[0] + side * h * 0.08f, sh[1] - h * 0.15f, sh[2]), floatArrayOf(sh[0] + side * h * 0.1f, sh[1] - h * 0.3f, sh[2]))
            fun d(i: Int): Float { val dx = p[i * 3] - sh[0]; val dy = p[i * 3 + 1] - sh[1]; val dz = p[i * 3 + 2] - sh[2]; return dx * dx + dy * dy + dz * dz }
            val far = cand.sortedByDescending { d(it) }.take(maxOf(4, cand.size / 50))
            val hand = FloatArray(3).also { o -> far.forEach { i -> for (k in 0..2) o[k] += p[i * 3 + k] / far.size } }
            // elbow: midpoint, bent slightly outward and back
            val el = floatArrayOf((sh[0] + hand[0]) / 2 + side * h * 0.03f, (sh[1] + hand[1]) / 2, (sh[2] + hand[2]) / 2 - h * 0.02f)
            return Triple(sh, el, hand)
        }
        val (shL, elL, haL) = arm(1f); val (shR, elR, haR) = arm(-1f)
        // arm thickness: how far the geometry clearly outside the torso sits from the upper arm. Arms only claim
        // vertices within that reach, so a skirt, a coat tail or wide hips next to a hanging hand stay with the body.
        val armR = run {
            val ds = ArrayList<Float>()
            for ((sh, el, side) in listOf(Triple(shL, elL, 1f), Triple(shR, elR, -1f))) for (i in 0 until V) {
                val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                if ((x - cx) * side > torsoHalf * 1.1f && y < sh[1] && y > el[1] && z > cz - h * 0.05f) ds += segDist(x, y, z, sh, el) }
            if (ds.size < 20) torsoHalf * 0.6f else { ds.sort(); (ds[ds.size * 9 / 10] * 1.3f).coerceIn(torsoHalf * 0.3f, torsoHalf * 0.8f) }
        }
        val pelvisY = if (hipY.isNaN()) minY + (shY - minY) * 0.3f else hipY
        // wings: a lot of geometry well behind the back, above the pelvis, reaching out to the sides
        val wingBones = IntArray(4) { -1 }
        for ((si, side) in listOf(0 to 1f, 1 to -1f)) {
            val cand = ArrayList<Int>()
            for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                if (fullBody && (x - cx) * side > torsoHalf * 1.25f && y > shY - h * 0.1f && y < headTop && z < cz + h * 0.02f) cand += i }
            if (cand.size < V / 200) continue
            val base = floatArrayOf(cx + side * torsoHalf * 0.4f, shY - h * 0.03f, cz - h * 0.06f)
            fun d(i: Int): Float { val dx = p[i * 3] - base[0]; val dy = p[i * 3 + 1] - base[1]; val dz = p[i * 3 + 2] - base[2]; return dx * dx + dy * dy + dz * dz }
            val far = cand.sortedByDescending { d(it) }.take(maxOf(4, cand.size / 30))
            val tipP = FloatArray(3).also { o -> far.forEach { i -> for (k in 0..2) o[k] += p[i * 3 + k] / far.size } }
            val mid = floatArrayOf((base[0] + tipP[0]) / 2, (base[1] + tipP[1]) / 2 + h * 0.03f, (base[2] + tipP[2]) / 2)
            val sfx = if (side > 0) "L" else "R"
            wingBones[si * 2] = addBone("wing.$sfx", CHEST, base); wingBones[si * 2 + 1] = addBone("wingtip.$sfx", wingBones[si * 2], mid)
        }
        // tail: geometry behind the body below the waist, near the middle line; a 5-bone chain along it
        val TB = 5
        val tailBones = IntArray(TB) { -1 }
        run {
            val cand = ArrayList<Int>()
            for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                if (z < cz - h * 0.1f && y < pelvisY + h * 0.08f && abs(x - cx) < torsoHalf * 1.5f) cand += i }
            if (cand.size < V / 150) return@run
            val start = floatArrayOf(cx, pelvisY - h * 0.03f, cz - h * 0.08f)
            fun d(i: Int): Float { val dx = p[i * 3] - start[0]; val dy = p[i * 3 + 1] - start[1]; val dz = p[i * 3 + 2] - start[2]; return sqrt(dx * dx + dy * dy + dz * dz) }
            val dmax = cand.maxOf { d(it) }.coerceAtLeast(1e-4f)
            val farEnd = cand.filter { d(it) > dmax * 0.8f }; val endZ = farEnd.map { p[it * 3 + 2] }.average().toFloat()
            if (dmax < h * 0.2f || endZ > start[2] - h * 0.25f) return@run     // just the back of the body, not a tail
            var parent = 0; var prev = start
            for (k in 0 until TB) {
                val lo = dmax * k / TB; val hi = dmax * (k + 1) / TB; var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
                for (i in cand) { val dd = d(i); if (dd in lo..hi) { sx += p[i * 3]; sy += p[i * 3 + 1]; sz += p[i * 3 + 2]; n++ } }
                val c = if (n > 0) floatArrayOf((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat()) else prev
                val at = if (k == 0) start else c
                tailBones[k] = addBone("tail${k + 1}", parent, at); parent = tailBones[k]; prev = c
            }
        }
        fun isWingSide(i: Int, side: Float) = (p[i * 3] - cx) * side > torsoHalf * 1.25f && p[i * 3 + 1] > shY - h * 0.25f ||
            (p[i * 3 + 2] < cz - h * 0.1f && (p[i * 3] - cx) * side > torsoHalf * 0.4f && p[i * 3 + 1] > shY - h * 0.15f)
        // mouth: just below the front-most point of the face (snout / nose tip), found among head vertices near the
        // middle line and below the upper part of the head (so horns, crests and ears don't count)
        var tip = -1; val headSpan = headTop - neckY
        for (i in 0 until V) { val y = p[i * 3 + 1]; if (y <= neckY || y > neckY + headSpan * 0.6f || abs(p[i * 3] - headC[0]) > headR * 0.35f) continue
            if (tip < 0 || p[i * 3 + 2] > p[tip * 3 + 2]) tip = i }
        val mouth = if (tip >= 0) floatArrayOf(p[tip * 3], p[tip * 3 + 1] - h * 0.025f, p[tip * 3 + 2] - h * 0.01f) else floatArrayOf(headC[0], neckY + headSpan * 0.3f, headC[2] + headR)
        val jaw = jawGeometry(p, V, headC, headSpan, headR, neckY, mouth, h, m.nrm)
        // the jaw is the part of the jaw box that is connected over the surface to the mouth; a chest or the top of the
        // breasts that merely lies inside the box (a lowered head over the bust) is not (t482u)
        val jawReach: BooleanArray? = if (m.idx.isEmpty()) null else run {
            val rep = weld(p, V, h); val (first, nb) = adjacency(rep, m.idx, V)
            val inBox = BooleanArray(V) { rep[it] == it && jaw.weight(p[it * 3], p[it * 3 + 1], p[it * 3 + 2]) > 0f }
            val reach = BooleanArray(V); val queue = IntArray(V); var qh = 0; var qt = 0
            val r2 = (headR * 0.6f) * (headR * 0.6f)
            for (i in 0 until V) if (inBox[i]) { val dx = p[i * 3] - mouth[0]; val dy = p[i * 3 + 1] - mouth[1]; val dz = p[i * 3 + 2] - mouth[2]
                if (dx * dx + dy * dy + dz * dz < r2) { reach[i] = true; queue[qt++] = i } }
            if (qt == 0) return@run null
            while (qh < qt) { val a = queue[qh++]; for (e in first[a] until first[a + 1]) { val b = nb[e]; if (!reach[b] && inBox[b]) { reach[b] = true; queue[qt++] = b } } }
            BooleanArray(V) { reach[rep[it]] }
        }
        fun jawW(i: Int): Float = if (jawReach != null && !jawReach[i]) 0f else jaw.weight(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
        val mouthY = mouth[1]
        val jawPivot = jaw.pivot
        // ---- ears (or horns): a tall part rising from each upper side of the head ----
        val earBones = IntArray(2) { -1 }; val earBase = Array(2) { FloatArray(3) }; val earTip = Array(2) { FloatArray(3) }
        for ((si, side) in listOf(0 to 1f, 1 to -1f)) {
            val cand = ArrayList<Int>()
            for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]
                if (y > neckY + headSpan * 0.55f && (x - headC[0]) * side > headR * 0.25f && abs(p[i * 3 + 2] - headC[2]) < headR * 1.3f && abs(x - headC[0]) < headR * 2.2f) cand += i }
            if (cand.size < maxOf(20, V / 3000)) continue
            val top = cand.sortedByDescending { p[it * 3 + 1] }.take(maxOf(4, cand.size / 20))
            val tipP = FloatArray(3).also { o -> top.forEach { i -> for (k in 0..2) o[k] += p[i * 3 + k] / top.size } }
            val base = floatArrayOf(headC[0] + side * headR * 0.45f, neckY + headSpan * 0.68f, headC[2])
            // a real ear rises well above where it starts, and sits clearly off the middle line (not the crown)
            if (tipP[1] - base[1] < headSpan * 0.22f || (tipP[0] - headC[0]) * side < headR * 0.3f) continue
            earBase[si] = base; earTip[si] = tipP
            earBones[si] = addBone("ear.${if (side > 0) "L" else "R"}", HEAD, base)
        }
        fun earWeight(si: Int, x: Float, y: Float, z: Float): Float {
            if (earBones[si] < 0) return 0f
            val b = earBase[si]; val t = earTip[si]; val side = if (si == 0) 1f else -1f
            if (y < b[1] || (x - headC[0]) * side < headR * 0.2f) return 0f
            val len = sqrt((t[0] - b[0]) * (t[0] - b[0]) + (t[1] - b[1]) * (t[1] - b[1]) + (t[2] - b[2]) * (t[2] - b[2]))
            if (segDist(x, y, z, b, t) > len * 0.45f) return 0f
            return ((y - b[1]) / ((t[1] - b[1]) * 0.35f)).coerceIn(0f, 1f)
        }
        // ---- chest / breasts: a bust that sticks out in front of the waist goes with the chest as one piece ----
        val breastBones = IntArray(2) { -1 }; val breastC = Array(2) { FloatArray(3) }; val breastR = FloatArray(2)
        var bustPlane = Float.NaN; var bustDepth = 0f; var bustY0 = 0f; var bustY1 = 0f
        run {
            val N = 16; val y0 = pelvisY; val y1 = shY; if (y1 - y0 < h * 0.05f) return@run
            val front = FloatArray(N) { -Float.MAX_VALUE }
            for (i in 0 until V) { val y = p[i * 3 + 1]; if (y < y0 || y > y1 || abs(p[i * 3] - cx) > torsoHalf * 1.2f) continue
                val k = (((y - y0) / (y1 - y0)) * N).toInt().coerceIn(0, N - 1); front[k] = maxOf(front[k], p[i * 3 + 2]) }
            val lower = (0 until N * 4 / 10).map { front[it] }.filter { it > -Float.MAX_VALUE }
            val upper = (N * 4 / 10 until N).filter { front[it] > -Float.MAX_VALUE }
            if (lower.isEmpty() || upper.isEmpty()) return@run
            val kMax = upper.maxBy { front[it] }; val chestMax = front[kMax]
            // the waist is the flattest front between the bust and the hips (above a skirt that flares forward)
            val waist = ((N * 2 / 10) until kMax).map { front[it] }.filter { it > -Float.MAX_VALUE }.minOrNull() ?: lower.minOrNull()!!
            val bulge = chestMax - waist
            if (bulge < h * 0.025f || bulge < (chestMax - cz) * 0.2f) return@run
            bustPlane = waist + bulge * 0.3f; bustDepth = chestMax - bustPlane
            var k0 = kMax; while (k0 > 0 && front[k0 - 1] > bustPlane) k0--; var k1 = kMax; while (k1 < N - 1 && front[k1 + 1] > bustPlane) k1++
            bustY0 = y0 + (y1 - y0) * k0 / N - h * 0.01f; bustY1 = y0 + (y1 - y0) * (k1 + 1) / N + h * 0.01f
            // per-side centroid + spread of the bust surface in front of the bust plane
            val cs = arrayOfNulls<FloatArray>(2); val rs = FloatArray(2)
            for ((si, side) in listOf(0 to 1f, 1 to -1f)) {
                var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
                for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                    if (y < bustY0 || y > bustY1 || z < bustPlane || (x - cx) * side < 0 || abs(x - cx) > torsoHalf * 1.3f) continue
                    sx += x; sy += y; sz += z; n++ }
                if (n < 10) continue
                val c = floatArrayOf((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())
                var rr = 0.0; for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                    if (y < bustY0 || y > bustY1 || z < bustPlane || (x - cx) * side < 0 || abs(x - cx) > torsoHalf * 1.3f) continue
                    rr += (x - c[0]) * (x - c[0]) + (y - c[1]) * (y - c[1]) + (z - c[2]) * (z - c[2]) }
                cs[si] = c; rs[si] = sqrt(rr / n).toFloat()
            }
            if (cs[0] == null && cs[1] == null) return@run
            // symmetric: both breasts get the same (mirrored) centre and the larger size, so a mesh where one side
            // came out flatter or less sampled is still rigged evenly on both sides
            val dx = listOfNotNull(cs[0]?.let { abs(it[0] - cx) }, cs[1]?.let { abs(it[0] - cx) }).average().toFloat()
            val my = listOfNotNull(cs[0]?.get(1), cs[1]?.get(1)).average().toFloat(); val mz = listOfNotNull(cs[0]?.get(2), cs[1]?.get(2)).average().toFloat()
            val r = maxOf(rs[0], rs[1]) * 1.9f
            for ((si, side) in listOf(0 to 1f, 1 to -1f)) {
                val c = floatArrayOf(cx + side * dx, my, mz)
                breastC[si] = c; breastR[si] = r
                // the bone sits inside the chest behind the breast, so it swings / bounces from its root
                breastBones[si] = addBone("breast.${if (side > 0) "L" else "R"}", CHEST, floatArrayOf(c[0], c[1], cz + (c[2] - cz) * 0.3f))
            }
        }
        /** Breast influence at a point: the WHOLE breast and anything lying over it (a dress, a top, armour, fused or a
         *  separate shell) inside a smooth ellipsoid around the breast, fading out smoothly (no hard border anywhere,
         *  so nothing can tear at the edge of the region). The depth ramp starts a little behind the bust plane (a
         *  thickness band) so the root of the breast blends into the chest. */
        fun bustWeight(si: Int, x: Float, y: Float, z: Float): Float {
            if (breastBones[si] < 0 || bustPlane.isNaN()) return 0f
            val c = breastC[si]; val R = breastR[si]
            val d = sqrt((x - c[0]) * (x - c[0]) + (y - c[1]) * (y - c[1]) * 1.15f + (z - c[2]) * (z - c[2]) * 0.6f) / R
            if (d >= 1f) return 0f
            val rad = 1f - ((d - 0.45f) / 0.55f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            val band = bustDepth.coerceAtLeast(h * 0.01f)
            val f = ((z - (bustPlane - band * 0.6f)) / (band * 1.6f)).coerceIn(0f, 1f)
            return rad * f * f * (3 - 2 * f) * 0.9f
        }
        // arm reach over the surface: grow each arm from its clearly-outside-the-torso upper part, only through
        // vertices inside the arm's tube. Geometry that is merely near the arm in space (a skirt or hips under a
        // hanging hand) isn't connected to it inside the tube, so it never follows the arm.
        val armReach = if (!fullBody || m.idx.isEmpty()) null else run {
            val rep = weld(p, V, h); val adj = adjacency(rep, m.idx, V)
            val reach = BooleanArray(V)
            for ((sh, el, ha, side) in listOf(arrayOf(shL, elL, haL, floatArrayOf(1f)), arrayOf(shR, elR, haR, floatArrayOf(-1f)))) {
                val sd = side[0]
                fun inTube(i: Int): Boolean { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                    if ((x - cx) * sd < torsoHalf * 0.5f) return false
                    return segDist(x, y, z, sh, el) <= armR || segDist(x, y, z, el, ha) <= armR || segDist(x, y, z, ha, ha) <= armR * 1.4f }
                val queue = IntArray(V); var qh = 0; var qt = 0
                for (i in 0 until V) { if (rep[i] != i) continue; val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
                    if ((x - cx) * sd > torsoHalf * 1.1f && y < sh[1] && y > el[1] && segDist(x, y, z, sh, el) < armR * 0.7f && !reach[i]) { reach[i] = true; queue[qt++] = i } }
                while (qh < qt) { val a = queue[qh++]
                    for (e in adj.first[a] until adj.first[a + 1]) { val b = adj.second[e]; if (!reach[b] && inTube(b)) { reach[b] = true; queue[qt++] = b } } }
            }
            BooleanArray(V) { reach[rep[it]] }
        }
        val head = floatArrayOf(headC[0], neckY + (headTop - neckY) * 0.15f, headC[2])
        val W = arrayOf(root, spine, chest, neck, head, jawPivot, shL, elL, haL, shR, elR, haR, *extraW.toTypedArray())
        val names: Array<String> = NAMES + extraNames; val parents = PARENT + extraParent.toIntArray()
        val J = names.size
        if (DEBUG_RELAX) { val js = (0 until V).filter { jaw.weight(p[it * 3], p[it * 3 + 1], p[it * 3 + 2]) > 0f }
            println("rig dbg: h=$h minY=$minY neckY=${(neckY - minY) / h} shY=${(shY - minY) / h} bust=${(bustY0 - minY) / h}..${(bustY1 - minY) / h} plane=$bustPlane fullBody=$fullBody torsoHalf=${torsoHalf / h} jaw box ${js.size} y ${js.minOfOrNull { (p[it * 3 + 1] - minY) / h }}..${js.maxOfOrNull { (p[it * 3 + 1] - minY) / h }} reach ${jawReach?.count { it }} mouth y ${(mouth[1] - minY) / h} headTop ${(headTop - minY) / h}") }
        /** The bust and the chest front below its top: never the head's or the jaw's. */
        fun bustZone(x: Float, y: Float, z: Float) = !bustPlane.isNaN() && ((bustWeight(0, x, y, z) > 0f || bustWeight(1, x, y, z) > 0f) ||
            (y < bustY1 + h * 0.03f && z > cz && abs(x - cx) < torsoHalf * 1.4f))
        // ---- weights ----
        val joints = IntArray(V * 4); val weights = FloatArray(V * 4); var jawCount = 0
        val segA = IntArray(J) { it }; val segB = IntArray(J) { j -> (0 until J).firstOrNull { parents[it] == j && it != JAW && (j != 0 || it == SPINE) && (j != CHEST || it == NECK) } ?: -1 }
        val segEnd = Array(J) { j -> if (segB[j] >= 0) W[segB[j]] else when (j) { HEAD -> floatArrayOf(head[0], headTop, head[2]); else -> W[j] } }
        val dist = FloatArray(J)
        for (i in 0 until V) {
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            // the bust is never part of the head: a lowered head / long snout puts the jaw box (and the head's slice)
            // over the top of the breasts, which then opened with the mouth and nodded with the head (t482u)
            val inBust = bustZone(x, y, z)
            // lower jaw: below the mouth line, in front of the hinge; blends into the head just in front of the hinge
            val jw = if (inBust) 0f else jawW(i)
            if (jw > 0f) {
                jawCount++
                if (jw >= 1f || style == "rigid") { joints[i * 4] = JAW; weights[i * 4] = 1f }
                else { joints[i * 4] = JAW; weights[i * 4] = jw; joints[i * 4 + 1] = HEAD; weights[i * 4 + 1] = 1f - jw }
                continue
            }
            val wingish = (wingBones[0] >= 0 && isWingSide(i, 1f)) || (wingBones[2] >= 0 && isWingSide(i, -1f))
            for (si in 0..1) { val ew = earWeight(si, x, y, z); if (ew > 0f) {
                if (ew >= 1f || style == "rigid") { joints[i * 4] = earBones[si]; weights[i * 4] = 1f }
                else { joints[i * 4] = earBones[si]; weights[i * 4] = ew; joints[i * 4 + 1] = HEAD; weights[i * 4 + 1] = 1f - ew }
                break } }
            if (weights[i * 4] > 0f) continue
            if (!inBust && !wingish && y > neckY + (headTop - neckY) * 0.05f && abs(x - headC[0]) < headR * 1.8f) { joints[i * 4] = HEAD; weights[i * 4] = 1f; continue }
            // the bust: chest (and its breast bone), never an arm or the spine, so it moves as one piece
            run { val bL = bustWeight(0, x, y, z); val bR = bustWeight(1, x, y, z); val si = if (bL >= bR) 0 else 1; val bw = maxOf(bL, bR)
                if (bw > 0f || (!bustPlane.isNaN() && z > bustPlane && y in bustY0..bustY1 && abs(x - cx) < torsoHalf * 1.1f)) {
                    if (style == "rigid" || bw <= 0f) { joints[i * 4] = if (style == "rigid" && bw > 0.5f) breastBones[si] else CHEST; weights[i * 4] = 1f }
                    else {
                        // both sides blend in the cleavage; the rest stays on the chest
                        val sum = bL + bR; val k = if (sum > 0.9f) 0.9f / sum else 1f
                        var o = 0
                        joints[i * 4 + o] = CHEST; weights[i * 4 + o] = 1f - sum * k; o++
                        if (bL > 0f) { joints[i * 4 + o] = breastBones[0]; weights[i * 4 + o] = bL * k; o++ }
                        if (bR > 0f) { joints[i * 4 + o] = breastBones[1]; weights[i * 4 + o] = bR * k }
                    }
                } }
            if (weights[i * 4] > 0f) continue
            for (j in 0 until J) { if (j == JAW || j == HEAD) { dist[j] = Float.MAX_VALUE; continue }; dist[j] = segDist(x, y, z, W[segA[j]], segEnd[j]) }
            // arms only claim vertices on their own side, outside the torso
            for (j in intArrayOf(6, 7, 8)) if ((x - cx) < torsoHalf * 0.5f) dist[j] = Float.MAX_VALUE
            for (j in intArrayOf(9, 10, 11)) if ((cx - x) < torsoHalf * 0.5f) dist[j] = Float.MAX_VALUE
            if (fullBody) for (j in 6..11) if (dist[j] > armR * (if (j == 8 || j == 11) 1.4f else 1f)) dist[j] = Float.MAX_VALUE
            if (armReach != null && !armReach[i]) for (j in 6..11) dist[j] = Float.MAX_VALUE
            // legs only below the hips on their own side; wings only behind on their side; tail only behind and low
            for (k in 0..7) { val j = legBones[k]; if (j < 0) continue; val side = if (k < 4) 1f else -1f
                if (y > hipY + h * 0.02f || (x - cx) * side < -h * 0.01f) dist[j] = Float.MAX_VALUE }
            for (k in 0..3) { val j = wingBones[k]; if (j < 0) continue; if (!isWingSide(i, if (k < 2) 1f else -1f)) dist[j] = Float.MAX_VALUE }
            for (j in tailBones) { if (j < 0) continue; if (z > cz - h * 0.08f || y > pelvisY + h * 0.08f) dist[j] = Float.MAX_VALUE }
            for (j in earBones) if (j >= 0) dist[j] = Float.MAX_VALUE
            for (j in breastBones) if (j >= 0) dist[j] = Float.MAX_VALUE
            if (wingish) for (j in 0 until NAMES.size) if (j != CHEST) dist[j] = Float.MAX_VALUE
            var a = 0; for (j in 1 until J) if (dist[j] < dist[a]) a = j
            if (style == "rigid") { joints[i * 4] = a; weights[i * 4] = 1f; continue }
            var c = if (a == 0) 1 else 0; for (j in 0 until J) if (j != a && dist[j] < dist[c]) c = j
            val wa = 1f / (dist[a] * dist[a] * dist[a] * dist[a] + 1e-12f); val wc = 1f / (dist[c] * dist[c] * dist[c] * dist[c] + 1e-12f)
            joints[i * 4] = a; joints[i * 4 + 1] = c; weights[i * 4] = wa / (wa + wc); weights[i * 4 + 1] = wc / (wa + wc)
        }
        if (style != "rigid") {
            val keep = BooleanArray(J); keep[JAW] = true
            for (j in wingBones) if (j >= 0) keep[j] = true
            for (j in tailBones) if (j >= 0) keep[j] = true
            for (j in earBones) if (j >= 0) keep[j] = true
if (DEBUG_RELAX) println("breast stage %s: L=%d R=%d".format("assign", (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[0] && weights[i * 4 + it] > 0.05f } }, (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[1] && weights[i * 4 + it] > 0.05f } }))
            transferAndSmooth(p, V, m.idx, joints, weights, keep, h)
            if (DEBUG_RELAX) println("breast stage %s: L=%d R=%d".format("smooth", (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[0] && weights[i * 4 + it] > 0.05f } }, (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[1] && weights[i * 4 + it] > 0.05f } }))
            // smoothing leaks a little jaw weight onto whatever touches the mouth (a chest under a lowered snout):
            // only the jaw region itself follows the jaw
            for (i in 0 until V) { if (jawW(i) > 0f && !bustZone(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])) continue
                var jw = 0f; for (k in 0..3) if (joints[i * 4 + k] == JAW) jw += weights[i * 4 + k]
                if (jw <= 0f || jw >= 0.5f) continue
                for (k in 0..3) if (joints[i * 4 + k] == JAW) { weights[i * 4 + k] = 0f; joints[i * 4 + k] = 0 }
                val rest = 1f - jw; if (rest > 1e-6f) for (k in 0..3) weights[i * 4 + k] /= rest }
        }
        // skeleton: rest translations relative to parent, identity rotations, inverse bind = translate(-world)
        val t = FloatArray(J * 3); val r = FloatArray(J * 4) { if (it % 4 == 3) 1f else 0f }; val s = FloatArray(J * 3) { 1f }; val ibm = FloatArray(J * 16)
        val world = FloatArray(J * 3)
        for (j in 0 until J) { for (k in 0..2) { world[j * 3 + k] = W[j][k]; t[j * 3 + k] = W[j][k] - (if (parents[j] >= 0) W[parents[j]][k] else 0f) }
            for (k in 0 until 16) ibm[j * 16 + k] = if (k % 5 == 0) 1f else 0f; ibm[j * 16 + 12] = -W[j][0]; ibm[j * 16 + 13] = -W[j][1]; ibm[j * 16 + 14] = -W[j][2] }
        val out = Mesh3D(m.pos, m.nrm, m.uv, m.col, joints, weights, m.idx, m.texture, m.textureMime, m.pmParent, m.pmTriDeath)
        // no vertex ended up on the jaw (a mouth line that only found the throat / chest, now excluded): no jaw bone
        // by name, so lip-sync falls back to a small head nod instead of moving nothing
        if (jawCount == 0) names[JAW] = "hinge_unused"
        out.skeleton = Skeleton(names, parents, t, r, s, ibm); out.animations = emptyList(); out.pmCleanTris = m.pmCleanTris
        // no-tear pass over the bust: the breasts and everything over / around them (dress, top, the arm resting on
        // the side of the breast), anywhere the breast field reaches plus a margin
        var bodyForbid: Set<Int> = emptySet(); var bodyForbidAt: BooleanArray? = null
        if (style != "rigid" && RELAX && breastBones[0] >= 0) {
            val field = FloatArray(V) { i -> maxOf(bustWeight(0, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]), bustWeight(1, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])) }
            val focus = BooleanArray(V) { field[it] > 0f }
            // the breast never follows an arm, the head or a wing (smoothing pulls their weight in where an arm rests
            // on the side of the breast or a lowered chin / wing touches it in a fused mesh): that influence moves to
            // the chest, fully where the breast field is strong and fading out toward its edge (no new hard border)
            // (only bleed: a vertex that mostly belongs to the arm / head / wing itself, e.g. a chin over the chest,
            // keeps its bones; the mouth is never touched)
            val arm = (intArrayOf(6, 7, 8, 9, 10, 11, HEAD) + wingBones.filter { it >= 0 } + earBones.filter { it >= 0 }).toSet()
            for (i in 0 until V) { if (field[i] <= 0f) continue
                if ((0..3).any { joints[i * 4 + it] == JAW && weights[i * 4 + it] > 0f }) { field[i] = 0f; continue }
                if ((0..3).sumOf { if (joints[i * 4 + it] in arm) weights[i * 4 + it].toDouble() else 0.0 } >= 0.5) { field[i] = 0f; continue }
                val f = (field[i] / 0.3f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                if ((0..3).none { joints[i * 4 + it] in arm && weights[i * 4 + it] > 0f }) continue
                val mp = HashMap<Int, Float>(6)
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w <= 0f) continue; val j = joints[i * 4 + k]
                    if (j in arm) { mp.merge(j, w * (1 - f), Float::plus); mp.merge(CHEST, w * f, Float::plus) } else mp.merge(j, w, Float::plus) }
                val top = mp.entries.filter { it.value > 0f }.sortedByDescending { it.value }.take(4); val tot = top.sumOf { it.value.toDouble() }.toFloat()
                for (k in 0..3) { val e = top.getOrNull(k); joints[i * 4 + k] = e?.key ?: 0; weights[i * 4 + k] = (e?.value ?: 0f) / tot } }
            val strong = BooleanArray(V) { field[it] >= 0.3f }
            if (DEBUG_RELAX) println("breast stage %s: L=%d R=%d".format("strip", (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[0] && weights[i * 4 + it] > 0.05f } }, (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[1] && weights[i * 4 + it] > 0.05f } }))
            relaxStretch(p, V, m.idx, joints, weights, out.skeleton!!, h, JAW, focus = focus, focusBones = breastBones, forbid = arm, forbidAt = strong)
            bodyForbid = arm; bodyForbidAt = strong
            if (DEBUG_RELAX) println("breast stage %s: L=%d R=%d".format("relax", (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[0] && weights[i * 4 + it] > 0.05f } }, (0 until V).count { i -> (0..3).any { joints[i * 4 + it] == breastBones[1] && weights[i * 4 + it] > 0.05f } }))
        }
        // the rest of the body: seams between neighbouring bones (head / neck, shoulder, hip, tail root, wing root)
        // blend until no edge stretches more than 10 %; the bust keeps its own pass's rules (no arm / head / wing on
        // the breast, a breast bone never spreads). Where two parts that are not neighbours are fused (a hand resting
        // on a thigh, a wing tip lying on the head), no weight blend can absorb the motion, so the motion of those
        // parts is toned down until every edge holds (limitMotion)
        // the pre-v5 weighting (smooth weights + the bust pass): full motion, what the amplitude check compares with
        lastBaseWeights = joints.copyOf() to weights.copyOf()
        // the rest of the body (v6). Where two parts of a fused mesh touch (a hand on the hip, a wing tip on the tail, a
        // chin on the chest) or meet at a joint, the contact pass fades both toward the bone they share until no edge
        // stretches more than 10 %. That alone would freeze whole parts (a wing lying on the tail can't flap without
        // tearing it), so the motion budget then gives every part back as much of its full motion as it needs to keep
        // ≥ MOTION_KEEP of it: motion wins over stretch, nothing is toned down silently, and [lastMotion] reports
        // what is left on each side of the trade-off.
        if (style != "rigid" && RELAX) {
            val bust = lastRelax
            val frozen = BooleanArray(V) { i -> (bodyForbidAt?.get(i) == true) || (0..3).any { k -> joints[i * 4 + k] in breastBones && weights[i * 4 + k] > 0.05f } }
            val sk0 = out.skeleton!!
            // joint seams first (the two sides' main bones are parent / child: head / neck, ear / head, tail segments,
            // shoulder, wing root): blended wider until they hold, which costs no motion; the bust keeps its own pass
            fun domBone(v: Int): Int { var b0 = 0; for (k in 1..3) if (weights[v * 4 + k] > weights[v * 4 + b0]) b0 = k; return joints[v * 4 + b0] }
            fun jointSeam(a: Int, b: Int): Boolean { if (frozen[a] || frozen[b]) return false; val x = domBone(a); val y = domBone(b); return x != y && (sk0.parent[x] == y || sk0.parent[y] == x) }
            if (System.getProperty("rig.seam") != "none")
                relaxStretch(p, V, m.idx, joints, weights, sk0, h, JAW, forbid = bodyForbid, forbidAt = bodyForbidAt, maxRho = SEAM_RHO, edgeOk = ::jointSeam, maxRounds = 30,
                    focusBones = breastBones.filter { it >= 0 }.toIntArray())
            val seam = lastRelax
            // the bust keeps exactly what its own pass gave it
            run { val (bj, bw) = lastBaseWeights!!; for (i in 0 until V) if (frozen[i]) for (k in 0..3) { joints[i * 4 + k] = bj[i * 4 + k]; weights[i * 4 + k] = bw[i * 4 + k] } }
            val fullJ = joints.copyOf(); val fullW = weights.copyOf()
            if (System.getProperty("rig.contact") != "none") anchorContacts(p, V, m.idx, joints, weights, sk0, h, JAW, frozen, forbid = bodyForbid, rounds = CONTACT_ROUNDS)
            val body = lastRelax
            sk0.motion = null
            lastSafe = joints.copyOf() to weights.copyOf()
            lastMotion = if (MOTION_KEEP > 0f) keepMotion(p, V, m.idx, joints, weights, fullJ, fullW, lastBaseWeights!!.first, lastBaseWeights!!.second, sk0, h, JAW, MOTION_KEEP) else "contact pass only"
            // the head, neck, shoulders and bust move fully and must not tear: what still tears against them follows them
            if (MOTION_KEEP > 0f && System.getProperty("rig.prio") == "on") {   // experiment: made head / bust worse (see tools/rig_v6_notes.md)
                val prio = IntArray(sk0.size) { j -> when {
                    j in breastBones || j == CHEST -> 3
                    j == HEAD || j == NECK || j == JAW || j in earBones -> 2
                    j in 6..11 -> 1
                    else -> 0 } }
                val strongBust = bodyForbidAt; val armSet = bodyForbid; val headSet = (intArrayOf(HEAD, JAW) + earBones.filter { it >= 0 }).toSet()
                val chestFront = BooleanArray(V) { i -> bustZone(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]) }
                priorityFollow(p, V, m.idx, joints, weights, sk0, h, JAW, prio, frozen) { v, j -> (strongBust?.get(v) == true && j in armSet) || (chestFront[v] && j in headSet) }
                lastMotion += "; " + lastRelax
            }
            lastRelax = (if (breastBones[0] >= 0) "bust $bust; " else "") + "joint seams $seam; body $body; $lastMotion"
        }
        if (style != "rigid" && RELAX) lastSafe?.let { (sj, sw) -> out.joints2 = sj; out.weights2 = sw }
        return Result(out, world, mouth, jawCount, jaw)
    }

    /** Mesh edges (between welded vertices, none into the jaw: the mouth is meant to open) and their worst stretch
     *  over a set of pose frames, skinned like the shader; frames are split over a few threads. */
    private class EdgeScan(val p: FloatArray, val V: Int, idx: IntArray, val joints: IntArray, val weights: FloatArray, val h: Float, jaw: Int) {
        val rep = weld(p, V, h); val first: IntArray; val nb: IntArray
        init { val (f, n) = adjacency(rep, idx, V); first = f; nb = n }
        /** a vertex that opens with the mouth (as the stretch test counts it) */
        val isJaw = BooleanArray(V) { i -> (0..3).any { joints[i * 4 + it] == jaw && weights[i * 4 + it] >= 0.01f } }
        fun len(a: Int, b: Int): Float { val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]; val dz = p[a * 3 + 2] - p[b * 3 + 2]; return sqrt(dx * dx + dy * dy + dz * dz) }
        val ea: IntArray; val eb: IntArray
        init { val a0 = IntList(V * 3); val b0 = IntList(V * 3)
            for (a in 0 until V) { if (rep[a] != a || isJaw[a]) continue
                for (e in first[a] until first[a + 1]) { val b = nb[e]; if (b > a && !isJaw[b] && len(a, b) > h * 1e-4f) { a0.add(a); b0.add(b) } } }
            ea = a0.a.copyOf(a0.size); eb = b0.a.copyOf(b0.size) }
        val E get() = ea.size
        val rest = FloatArray(ea.size) { len(ea[it], eb[it]) }
        val allVerts = (0 until V).filter { rep[it] == it }.toIntArray()
        fun skin(m: FloatArray, verts: IntArray, q: FloatArray) { for (i in verts) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; var ox = 0f; var oy = 0f; var oz = 0f
            for (k in 0..3) { val w = weights[i * 4 + k]; if (w == 0f) continue; val bb = joints[i * 4 + k] * 16
                ox += w * (m[bb] * x + m[bb + 4] * y + m[bb + 8] * z + m[bb + 12]); oy += w * (m[bb + 1] * x + m[bb + 5] * y + m[bb + 9] * z + m[bb + 13]); oz += w * (m[bb + 2] * x + m[bb + 6] * y + m[bb + 10] * z + m[bb + 14]) }
            q[i * 3] = ox; q[i * 3 + 1] = oy; q[i * 3 + 2] = oz } }
        fun st(k: Int, q: FloatArray): Float { val a = ea[k]; val b = eb[k]; val dx = q[a * 3] - q[b * 3]; val dy = q[a * 3 + 1] - q[b * 3 + 1]; val dz = q[a * 3 + 2] - q[b * 3 + 2]
            return stretch(rest[k], sqrt(dx * dx + dy * dy + dz * dz), h) }
        /** Worst stretch per edge of [edges] (all when null; results indexed like [edges]) over [frames]; [wf] gets the frame. */
        fun scan(frames: List<FloatArray>, edges: IntArray? = null, verts: IntArray = allVerts, wf: IntArray? = null): FloatArray {
            val n = edges?.size ?: E; val T = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors(), frames.size))
            val worst = Array(T) { FloatArray(n) }; val wfs = Array(T) { IntArray(n) }
            val th = (0 until T).map { t -> Thread { val q = FloatArray(V * 3); val w = worst[t]; val ff = wfs[t]
                var fi = t; while (fi < frames.size) { skin(frames[fi], verts, q)
                    if (edges == null) { for (c in 0 until n) { val r = st(c, q); if (r > w[c]) { w[c] = r; ff[c] = fi } } }
                    else for (c in 0 until n) { val r = st(edges[c], q); if (r > w[c]) { w[c] = r; ff[c] = fi } }
                    fi += T } } }
            if (T == 1) th[0].run() else { th.forEach { it.start() }; th.forEach { it.join() } }
            val out = FloatArray(n)
            for (c in 0 until n) { var bt = 0; for (t in 1 until T) if (worst[t][c] > worst[bt][c]) bt = t; out[c] = worst[bt][c]; if (wf != null) wf[c] = wfs[bt][c] }
            return out
        }
    }

    /**
     * Contacts: an edge that still tears after the weight smoothing joins two parts that move differently (head /
     * neck, a hand resting on a thigh or on the dress, a wing tip lying on the tail). Around it, both parts are faded
     * toward the bone they share (their nearest common ancestor: the neck, the spine, the root): fully at the contact,
     * nothing beyond a distance along the surface that is long enough to spread the motion they would have there
     * relative to that bone (≤ 7 % per unit length, at most 35 % of the height). The contact then stays closed and the
     * part bends smoothly away from it. A few rounds, each with a longer fade, for what is left. Jaw vertices and
     * [frozen] ones (the bust, which has its own pass) are left alone.
     */
    private fun anchorContacts(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int, frozen: BooleanArray?, limit: Float = 1.10f, rounds: Int = 5, forbid: Set<Int> = emptySet(), edgeOk: ((Int, Int) -> Boolean)? = null) {
        val t0 = System.nanoTime()
        val es = EdgeScan(p, V, idx, joints, weights, h, jaw); val rep = es.rep; val first = es.first; val nb = es.nb; val isJaw = es.isJaw
        val ea = es.ea; val eb = es.eb; val E = es.E
        fun len(a: Int, b: Int) = es.len(a, b)
        val q = FloatArray(V * 3)
        fun skin(m: FloatArray, verts: IntArray) = es.skin(m, verts, q)
        val J = sk.size; val depth = IntArray(J) { var d = 0; var k = it; while (sk.parent[k] >= 0) { k = sk.parent[k]; d++ }; d }
        fun lca(a0: Int, b0: Int): Int { var a = a0; var b = b0
            while (depth[a] > depth[b]) a = sk.parent[a]; while (depth[b] > depth[a]) b = sk.parent[b]
            while (a != b && a >= 0 && b >= 0) { a = sk.parent[a]; b = sk.parent[b] }; return if (a < 0 || b < 0) 0 else a }
        val worst = FloatArray(E); var initial = -1; var left = 0
        for (round in 0 until rounds) {
            val frames = checkFrames(sk)
            es.scan(frames).copyInto(worst)
            val badE = (0 until E).filter { worst[it] > limit && (edgeOk == null || edgeOk(ea[it], eb[it])) }
            if (initial < 0) initial = badE.size; left = badE.size
            if (badE.isEmpty()) break
            // each contact vertex: the bone both sides share
            val anchorOf = HashMap<Int, Int>()
            for (k in badE) { val a = ea[k]; val b = eb[k]
                var l = -1; for (v in intArrayOf(a, b)) for (s in 0..3) if (weights[v * 4 + s] > 0.05f) { val j = joints[v * 4 + s]; l = if (l < 0) j else lca(l, j) }
                if (l < 0) continue
                for (v in intArrayOf(a, b)) { val o = anchorOf[v]; anchorOf[v] = if (o == null) l else lca(o, l) } }
            // how far each contact vertex moves relative to its anchor bone
            val src = anchorOf.keys.toIntArray(); val disp = FloatArray(V)
            for (m in frames) { skin(m, src); for (v in src) { val bb = anchorOf[v]!! * 16; val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
                val dx = q[v * 3] - (m[bb] * x + m[bb + 4] * y + m[bb + 8] * z + m[bb + 12]); val dy = q[v * 3 + 1] - (m[bb + 1] * x + m[bb + 5] * y + m[bb + 9] * z + m[bb + 13])
                val dz = q[v * 3 + 2] - (m[bb + 2] * x + m[bb + 6] * y + m[bb + 10] * z + m[bb + 14]); val d = sqrt(dx * dx + dy * dy + dz * dz); if (d > disp[v]) disp[v] = d } }
            // fade field per anchor bone: max over contacts of smoothstep(1 - surface distance / reach)
            val grow = Math.pow(1.6, round.toDouble()).toFloat()
            val fields = HashMap<Int, FloatArray>(); val dj = Dijkstra(V)
            for (anchor in anchorOf.values.toSet()) { val fa = fields.getOrPut(anchor) { FloatArray(V) }
                val srcs = src.filter { anchorOf[it] == anchor }
                // reach bins (powers of two from 1 % of the height): one bounded Dijkstra per bin
                val bins = srcs.groupBy { v -> val r = (disp[v] / 0.07f * grow).coerceIn(h * 0.01f, h * ANCHOR_REACH); var b = h * 0.01f; while (b < r) b *= 2f; minOf(b, h * ANCHOR_REACH) }
                for ((reach, vs) in bins) {
                    dj.reset(); for (v in vs) { dj.set(v, 0f); dj.push(0f, v) }
                    while (dj.isNotEmpty()) { val top = dj.pop(); val d = LongHeap.dist(top); val v = LongHeap.vert(top); if (d > dj.get(v)) continue
                        val t = 1f - d / reach; val f = t * t * (3 - 2 * t); if (f > fa[v]) fa[v] = f
                        for (e in first[v] until first[v + 1]) { val u = nb[e]; val nd = d + len(v, u); if (nd < reach && nd < dj.get(u)) { dj.set(u, nd); dj.push(nd, u) } } }
                }
            }
            // several anchors overlapping: the strongest fade sets how much moves to the anchors, shared between them in
            // proportion to their fades (continuous, so two neighbouring vertices never pick different anchors outright)
            val fl = fields.entries.map { it.key to it.value }; val mp = BoneMix(J)
            // the bust ([frozen]) takes the fade too (v6: a contact next to it otherwise left a hard border along its
            // edge), but never toward a bone it may not follow (an arm, the head, a wing)
            for (i in 0 until V) { if (rep[i] != i || isJaw[i]) continue
                val fz = frozen?.get(i) == true
                var f = 0f; var sum = 0f; for ((a, fa) in fl) { if (fz && a in forbid) continue; val x = fa[i]; if (x > f) f = x; sum += x }
                if (f <= 0f) continue
                mp.reset()
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) mp.add(joints[i * 4 + k], w * (1 - f)) }
                for ((a, fa) in fl) if (fa[i] > 0f && !(fz && a in forbid)) mp.add(a, f * fa[i] / sum)
                mp.writeTop4(i, joints, weights) }
            for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
            if (DEBUG_RELAX) println("contacts round $round: ${badE.size} edges over ${"%.2f".format(limit)}, ${src.size} contact vertices, anchors ${anchorOf.values.toSet().map { sk.names[it] }}, ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        lastRelax = "contact pass: $initial edges over ${"%.2f".format(limit)} at start, $left before the last round, ${(System.nanoTime() - t0) / 1_000_000} ms"
    }

    /**
     * v6 contact / seam pass ("the part that moves drags the skin it touches"). An edge that stretches more than
     * [limit] in some [checkFrames] pose joins two places that move differently: a joint seam (head / neck, shoulder,
     * tail root), or a contact where two parts of a fused mesh touch (a hand on the hip, a tail along the leg, a wing
     * tip on the back, a chin on the chest). Instead of slowing the parts down, the side that moves less follows the
     * side that moves more: around the contact its weights blend toward the moving side's weights, fully at the
     * contact and fading out along the surface over a distance long enough to spread the relative motion at ≤ ~7 %
     * strain (geodesic, through the mesh's own edges: nothing blends through the air). The moving part keeps all of
     * its motion; the surface it touches stretches smoothly with it. Exception: a vertex may not follow a bone in
     * [forbidden] (the bust never follows an arm, the head or a wing; the chest front never follows the head or the
     * jaw), so there the other side follows instead. A few rounds with longer fades for whatever is left.
     */
    private fun followContacts(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int,
                               forbidden: (Int, Int) -> Boolean, limit: Float = 1.10f, rounds: Int = 6, maxReach: Float = FOLLOW_REACH) {
        val t0 = System.nanoTime()
        val es = EdgeScan(p, V, idx, joints, weights, h, jaw); val rep = es.rep; val first = es.first; val nb = es.nb; val isJaw = es.isJaw
        val ea = es.ea; val eb = es.eb; val E = es.E
        val q = FloatArray(V * 3)
        val frames = checkFrames(sk)
        fun bad(v: Int, tj: IntArray, tw: FloatArray) = (0..3).any { tw[it] > 0.02f && forbidden(v, tj[it]) }
        var initial = -1; var left = 0; var done = 0
        val boneMob = FloatArray(sk.size)
        fun dom(v: Int): Int { var b = 0; for (k in 1..3) if (weights[v * 4 + k] > weights[v * 4 + b]) b = k; return joints[v * 4 + b] }
        for (round in 0 until rounds) {
            val worst = es.scan(frames)
            val badE = (0 until E).filter { worst[it] > limit }
            if (initial < 0) initial = badE.size; left = badE.size; done = round
            if (badE.isEmpty()) break
            // how far each contact vertex moves (mobility) and how far the two ends of each bad edge move apart
            val cv = HashSet<Int>(); for (k in badE) { cv += ea[k]; cv += eb[k] }; val cva = cv.toIntArray()
            val mob = HashMap<Int, Float>(cva.size * 2); val rel = FloatArray(badE.size)
            for (m in frames) { es.skin(m, cva, q)
                for (v in cva) { val dx = q[v * 3] - p[v * 3]; val dy = q[v * 3 + 1] - p[v * 3 + 1]; val dz = q[v * 3 + 2] - p[v * 3 + 2]; val d = dx * dx + dy * dy + dz * dz; if (d > (mob[v] ?: 0f)) mob[v] = d }
                for ((n, k) in badE.withIndex()) { val a = ea[k]; val b = eb[k]
                    val dx = (q[a * 3] - q[b * 3]) - (p[a * 3] - p[b * 3]); val dy = (q[a * 3 + 1] - q[b * 3 + 1]) - (p[a * 3 + 1] - p[b * 3 + 1]); val dz = (q[a * 3 + 2] - q[b * 3 + 2]) - (p[a * 3 + 2] - p[b * 3 + 2])
                    val d = sqrt(dx * dx + dy * dy + dz * dz); if (d > rel[n]) rel[n] = d } }
            // which part moves more: per bone, the RMS motion of the vertices it dominates (so both sides of a seam
            // agree on who follows whom)
            if (round == 0) { val sum = DoubleArray(sk.size); val cnt = IntArray(sk.size)
                val sample = es.allVerts.filterIndexed { n, v -> n % 7 == 0 && !isJaw[v] }.toIntArray()
                for (m in frames) { es.skin(m, sample, q); for (v in sample) { val j = dom(v)
                    val dx = q[v * 3] - p[v * 3]; val dy = q[v * 3 + 1] - p[v * 3 + 1]; val dz = q[v * 3 + 2] - p[v * 3 + 2]; sum[j] += (dx * dx + dy * dy + dz * dz).toDouble(); cnt[j]++ } }
                for (j in 0 until sk.size) boneMob[j] = if (cnt[j] > 0) sqrt(sum[j] / cnt[j]).toFloat() else 0f }
            fun mobility(v: Int): Float = boneMob[dom(v)] + 1e-4f * (mob[v] ?: 0f)
            // sources: the end that moves less, with the other end's weights as its target
            val srcV = IntList(badE.size); val srcT = ArrayList<Pair<IntArray, FloatArray>>(); val srcR = ArrayList<Float>()
            for ((n, k) in badE.withIndex()) { var a = ea[k]; var b = eb[k]   // a follows b
                if (mobility(a) > mobility(b)) { val t = a; a = b; b = t }
                val bj = IntArray(4) { joints[b * 4 + it] }; val bw = FloatArray(4) { weights[b * 4 + it] }
                val aj = IntArray(4) { joints[a * 4 + it] }; val aw = FloatArray(4) { weights[a * 4 + it] }
                val (sv, tj, tw) = if (!bad(a, bj, bw)) Triple(a, bj, bw) else if (!bad(b, aj, aw)) Triple(b, aj, aw) else continue
                val grow = Math.pow(1.5, round.toDouble()).toFloat()
                srcV.add(sv); srcT += tj to tw; srcR += (rel[n] / 0.07f * grow).coerceIn(h * 0.01f, h * maxReach) }
            // multi-source Dijkstra per reach bin; each vertex keeps the nearest source's target and its fade
            val fade = FloatArray(V); val tgt = IntArray(V) { -1 }; val dj = Dijkstra(V); val from = IntArray(V)
            val bins = (0 until srcV.size).groupBy { n -> var b = h * 0.01f; while (b < srcR[n]) b *= 1.5f; minOf(b, h * maxReach) }
            for ((reach, ns) in bins) {
                dj.reset()
                fun push(d: Float, v: Int) = dj.push(d, v)
                for (n in ns) { val v = srcV[n]; dj.set(v, 0f); from[v] = n; push(0f, v) }
                while (dj.isNotEmpty()) { val top = dj.pop(); val d = LongHeap.dist(top); val v = LongHeap.vert(top)
                    if (d > dj.get(v)) continue
                    val n = from[v]; val t = 1f - d / reach; val f = t * t * (3 - 2 * t)
                    if (f > fade[v]) { fade[v] = f; tgt[v] = n }
                    for (e in first[v] until first[v + 1]) { val u = nb[e]; if (isJaw[u]) continue; val nd = d + es.len(v, u)
                        if (nd < reach && nd < dj.get(u)) { dj.set(u, nd); from[u] = n; push(nd, u) } } }
            }
            var changed = 0; val mp = BoneMix(sk.size)
            for (i in 0 until V) { if (rep[i] != i || isJaw[i] || tgt[i] < 0) continue; val f = fade[i]; if (f <= 0f) continue
                val (tj, tw) = srcT[tgt[i]]
                mp.reset()
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) mp.add(joints[i * 4 + k], w * (1 - f)) }
                for (k in 0..3) { val w = tw[k]; if (w > 0f && !forbidden(i, tj[k])) mp.add(tj[k], w * f) }
                if (!mp.writeTop4(i, joints, weights, skipIfZero = true)) continue; changed++ }
            for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
            if (DEBUG_RELAX) println("follow round $round: ${badE.size} edges over ${"%.2f".format(limit)}, ${srcV.size} sources, $changed vertices blended, ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        lastRelax = "contact pass: $initial edges over ${"%.2f".format(limit)} at start, $left in the last check (${done} rounds), ${(System.nanoTime() - t0) / 1_000_000} ms"
    }

    /**
     * v6 seam pass (geodesic heat diffusion). Every edge that still stretches more than [limit] in a [checkFrames]
     * pose marks a seam: two parts joined by surface that move differently (head / neck, shoulder / chest, a wing
     * root, a tail lying on a leg, a hand fused to a thigh). Around the seams the weights diffuse over the surface
     * itself: the welded mesh is cut into small patches (a grid cell ∩ a connected piece of surface, so a chin over the
     * chest or a hand over a separate thigh never blend through the air), the patch weights are averaged with their
     * neighbours a few times inside a mask that is 1 at the seam and fades out over a few patches, and every vertex
     * takes a smooth (distance-weighted) blend of its own and the neighbouring patches. The two parts then meet in
     * a band instead of on a line: both keep moving at their full procedural motion except right at the band.
     * Rounds repeat on what is left with bigger patches (wider bands). Jaw vertices are left alone (the mouth opens);
     * where [forbidAt] is set (the bust) the bones in [forbid] (arms, head, wings) are never blended in.
     */
    private fun heatSeams(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int,
                          forbid: Set<Int>, forbidAt: BooleanArray?, limit: Float = 1.10f, maxRounds: Int = 12, maxRho: Float = SEAM_RHO) {
        if (V == 0 || idx.isEmpty()) return
        val t0 = System.nanoTime()
        val es = EdgeScan(p, V, idx, joints, weights, h, jaw); val rep = es.rep; val first = es.first; val nb = es.nb; val isJaw = es.isJaw
        val ea = es.ea; val eb = es.eb; val E = es.E; val J = sk.size
        val frames = checkFrames(sk)
        val vs = es.allVerts.filter { !isJaw[it] }.toIntArray()
        val forb = BooleanArray(J) { it in forbid }
        var rho = h * 0.012f; var initial = -1; var nBad = 0; var rounds = 0; var lastBad = Int.MAX_VALUE
        val bestJ = joints.copyOf(); val bestW = weights.copyOf(); var best = Int.MAX_VALUE
        while (rounds < maxRounds) {
            val worst = es.scan(frames)
            nBad = (0 until E).count { worst[it] > limit }
            if (initial < 0) initial = nBad
            if (nBad < best) { best = nBad; joints.copyInto(bestJ); weights.copyInto(bestW) }
            if (nBad == 0) break
            // not much better than last round: wider bands
            if (rounds > 0 && nBad > lastBad * 0.6f) rho = minOf(rho * 1.6f, h * maxRho)
            lastBad = nBad; rounds++
            // ---- patches at this scale ----
            val inv = 1f / rho
            fun cell(i: Int) = ((Math.floor((p[i * 3] * inv).toDouble()).toLong() and 0x1FFFFF) shl 42) or ((Math.floor((p[i * 3 + 1] * inv).toDouble()).toLong() and 0x1FFFFF) shl 21) or (Math.floor((p[i * 3 + 2] * inv).toDouble()).toLong() and 0x1FFFFF)
            val ck = LongArray(V); for (i in vs) ck[i] = cell(i)
            val uf = IntArray(V) { it }
            fun fd(a: Int): Int { var x = a; while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x] }; return x }
            for (a in vs) for (e in first[a] until first[a + 1]) { val b = nb[e]; if (isJaw[b] || ck[b] != ck[a]) continue; val ra = fd(a); val rb = fd(b); if (ra != rb) uf[ra] = rb }
            val pid = IntArray(V) { -1 }; val rootId = HashMap<Int, Int>()
            for (i in vs) pid[i] = rootId.getOrPut(fd(i)) { rootId.size }
            val P = rootId.size
            val pw = FloatArray(P * J); val pc = FloatArray(P * 3); val pn = IntArray(P)
            for (i in vs) { val q = pid[i]; pn[q]++; for (k in 0..2) pc[q * 3 + k] += p[i * 3 + k]
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) pw[q * J + joints[i * 4 + k]] += w } }
            for (q in 0 until P) { val n = pn[q].toFloat(); for (k in 0..2) pc[q * 3 + k] /= n; for (j in 0 until J) pw[q * J + j] /= n; pw[q * J + jaw] = 0f }
            // patch adjacency (CSR)
            val pairs = HashSet<Long>()
            for (a in vs) for (e in first[a] until first[a + 1]) { val b = nb[e]; if (pid[b] < 0) continue; val x = pid[a]; val y = pid[b]; if (x != y) pairs += (minOf(x, y).toLong() shl 32) or maxOf(x, y).toLong() }
            val deg = IntArray(P + 1); for (pr in pairs) { deg[(pr ushr 32).toInt()]++; deg[(pr and 0xffffffffL).toInt()]++ }
            val pf = IntArray(P + 1); for (q in 0 until P) pf[q + 1] = pf[q] + deg[q]
            val fill = pf.copyOf(); val pnb = IntArray(pf[P])
            for (pr in pairs) { val x = (pr ushr 32).toInt(); val y = (pr and 0xffffffffL).toInt(); pnb[fill[x]++] = y; pnb[fill[y]++] = x }
            // mask: 1 on patches with a bad edge and their neighbours, fading over three more rings
            val ring = IntArray(P) { Int.MAX_VALUE }; val queue = java.util.ArrayDeque<Int>()
            for (k in 0 until E) if (worst[k] > limit) for (v in intArrayOf(ea[k], eb[k])) { val q = pid[v]; if (q >= 0 && ring[q] != 0) { ring[q] = 0; queue += q } }
            val RINGS = 4
            while (queue.isNotEmpty()) { val q = queue.poll(); if (ring[q] >= RINGS) continue
                for (e in pf[q] until pf[q + 1]) { val r = pnb[e]; if (ring[r] > ring[q] + 1) { ring[r] = ring[q] + 1; queue += r } } }
            val alpha = FloatArray(P) { q -> when { ring[q] <= 1 -> 1f; ring[q] > RINGS -> 0f; else -> { val t = 1f - (ring[q] - 1f) / RINGS; t * t * (3 - 2 * t) } } }
            // diffusion on the patch graph inside the mask
            val tmp = FloatArray(P * J)
            repeat(10) {
                pw.copyInto(tmp)
                for (q in 0 until P) { val a = alpha[q]; if (a <= 0f) continue; val n = pf[q + 1] - pf[q]; if (n == 0) continue
                    val o = q * J; val k = 0.6f * a / n
                    for (e in pf[q] until pf[q + 1]) { val r = pnb[e] * J; for (j in 0 until J) tmp[o + j] += k * (pw[r + j] - pw[o + j]) } }
                tmp.copyInto(pw) }
            // back to the vertices: a smooth blend of the own and neighbouring patches (by distance to their centres)
            val mix = FloatArray(J)
            for (i in vs) { val q = pid[i]
                var al = 0f; var sk2 = 0f; java.util.Arrays.fill(mix, 0f)
                fun take(r: Int) { val dx = p[i * 3] - pc[r * 3]; val dy = p[i * 3 + 1] - pc[r * 3 + 1]; val dz = p[i * 3 + 2] - pc[r * 3 + 2]
                    val d = sqrt(dx * dx + dy * dy + dz * dz) / rho; val kk = maxOf(0f, 1.6f - d).let { it * it }; if (kk <= 0f) return
                    sk2 += kk; al += kk * alpha[r]; for (j in 0 until J) mix[j] += kk * pw[r * J + j] }
                take(q); for (e in pf[q] until pf[q + 1]) take(pnb[e])
                if (sk2 <= 0f) continue; al /= sk2; if (al <= 1e-4f) continue
                for (j in 0 until J) mix[j] = mix[j] / sk2 * al
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) mix[joints[i * 4 + k]] += w * (1 - al) }
                if (forbidAt?.get(i) == true) for (j in 0 until J) if (forb[j]) mix[j] = 0f
                mix[jaw] = 0f
                // the four largest
                var tot = 0f
                for (k in 0..3) { var bj = -1; var bv = 0f; for (j in 0 until J) if (mix[j] > bv) { bv = mix[j]; bj = j }
                    if (bj < 0) { joints[i * 4 + k] = 0; weights[i * 4 + k] = 0f } else { joints[i * 4 + k] = bj; weights[i * 4 + k] = bv; tot += bv; mix[bj] = 0f } }
                if (tot > 0f) for (k in 0..3) weights[i * 4 + k] /= tot }
            for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
            if (DEBUG_RELAX) println("seams round $rounds: $nBad edges over ${"%.2f".format(limit)}, rho ${"%.3f".format(rho / h)} h, $P patches, ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        if (nBad > best) { bestJ.copyInto(joints); bestW.copyInto(weights); nBad = best }
        lastRelax = "seam pass: $initial edges over ${"%.2f".format(limit)} at start, $nBad after $rounds rounds (bands up to ${"%.3f".format(rho / h)} h), ${(System.nanoTime() - t0) / 1_000_000} ms"
        if (System.getProperty("rig.debug") != null || DEBUG_RELAX) println(lastRelax)
    }

    /** The no-stretch weighting of the last [rig] (motion budget 0): the "No stretching" end of the animation strength. */
    @Volatile var lastSafe: Pair<IntArray, FloatArray>? = null

    /**
     * Priority parts (bust / chest 3, head / neck / ears 2, arms 1, the rest 0). An edge that still stretches more than
     * [limit] between two parts of different priority: the lower one follows the higher one there (its weights fade toward
     * the leader's, fully at the contact, out along its own surface over a length that spreads their relative motion at
     * ≤ 7 %); a vertex of equal or higher priority is never changed, so the leaders keep their full motion. [frozen]
     * vertices (the bust) are never changed either.
     */
    private fun priorityFollow(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int,
                               prio: IntArray, frozen: BooleanArray, limit: Float = 1.10f, rounds: Int = 4, forbidden: (Int, Int) -> Boolean) {
        val t0 = System.nanoTime()
        val es = EdgeScan(p, V, idx, joints, weights, h, jaw); val rep = es.rep; val first = es.first; val nb = es.nb; val isJaw = es.isJaw
        val ea = es.ea; val eb = es.eb; val E = es.E; val frames = checkFrames(sk); val q = FloatArray(V * 3)
        fun dom(v: Int): Int { var b = 0; for (k in 1..3) if (weights[v * 4 + k] > weights[v * 4 + b]) b = k; return joints[v * 4 + b] }
        var initial = -1; var left = 0
        for (round in 0 until rounds) {
            val worst = es.scan(frames)
            val cand = (0 until E).filter { worst[it] > limit && prio[dom(ea[it])] != prio[dom(eb[it])] }
            if (initial < 0) initial = cand.size; left = cand.size; if (cand.isEmpty()) break
            val cv = HashSet<Int>(); for (k in cand) { cv += ea[k]; cv += eb[k] }; val cva = cv.toIntArray()
            val rel = FloatArray(cand.size)
            for (m in frames) { es.skin(m, cva, q)
                for ((n, k) in cand.withIndex()) { val a = ea[k]; val b = eb[k]
                    val dx = (q[a * 3] - q[b * 3]) - (p[a * 3] - p[b * 3]); val dy = (q[a * 3 + 1] - q[b * 3 + 1]) - (p[a * 3 + 1] - p[b * 3 + 1]); val dz = (q[a * 3 + 2] - q[b * 3 + 2]) - (p[a * 3 + 2] - p[b * 3 + 2])
                    val d = sqrt(dx * dx + dy * dy + dz * dz); if (d > rel[n]) rel[n] = d } }
            // follower vertex, its leader's weights, the leader's priority, the fade length
            val srcV = IntList(cand.size); val srcT = ArrayList<Pair<IntArray, FloatArray>>(); val srcP = IntList(cand.size); val srcR = ArrayList<Float>()
            val grow = Math.pow(1.5, round.toDouble()).toFloat()
            for ((n, k) in cand.withIndex()) { var a = ea[k]; var b = eb[k]; if (prio[dom(a)] > prio[dom(b)]) { val t = a; a = b; b = t }
                if (frozen[a]) continue
                srcV.add(a); srcT += IntArray(4) { joints[b * 4 + it] } to FloatArray(4) { weights[b * 4 + it] }; srcP.add(prio[dom(b)])
                srcR += (rel[n] / 0.07f * grow).coerceIn(h * 0.01f, h * 0.15f) }
            val fade = FloatArray(V); val tgt = IntArray(V) { -1 }; val dj = Dijkstra(V); val from = IntArray(V)
            val bins = (0 until srcV.size).groupBy { n -> var bb = h * 0.01f; while (bb < srcR[n]) bb *= 1.5f; minOf(bb, h * 0.15f) }
            for ((reach, ns) in bins) {
                dj.reset()
                fun push(d: Float, v: Int) = dj.push(d, v)
                for (n in ns) { val v = srcV[n]; dj.set(v, 0f); from[v] = n; push(0f, v) }
                while (dj.isNotEmpty()) { val top = dj.pop(); val d = LongHeap.dist(top); val v = LongHeap.vert(top)
                    if (d > dj.get(v)) continue
                    val n = from[v]; val t = 1f - d / reach; val f = t * t * (3 - 2 * t)
                    if (f > fade[v]) { fade[v] = f; tgt[v] = n }
                    for (e in first[v] until first[v + 1]) { val u = nb[e]; if (isJaw[u] || frozen[u] || prio[dom(u)] >= srcP[n]) continue; val nd = d + es.len(v, u)
                        if (nd < reach && nd < dj.get(u)) { dj.set(u, nd); from[u] = n; push(nd, u) } } }
            }
            val nj = joints.copyOf(); val nw = weights.copyOf(); val mp = BoneMix(sk.size)
            for (i in 0 until V) { if (rep[i] != i || isJaw[i] || tgt[i] < 0) continue; val f = fade[i]; if (f <= 0f) continue
                val (tj, tw) = srcT[tgt[i]]; mp.reset()
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) mp.add(joints[i * 4 + k], w * (1 - f)) }
                for (k in 0..3) { val w = tw[k]; if (w > 0f && !forbidden(i, tj[k])) mp.add(tj[k], w * f) }
                mp.writeTop4(i, nj, nw, skipIfZero = true) }
            nj.copyInto(joints); nw.copyInto(weights)
            for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
        }
        lastRelax = "priority pass: $initial edges over ${"%.2f".format(limit)} between parts of different priority, $left in the last check, ${(System.nanoTime() - t0) / 1_000_000} ms"
    }

    /** Share of its full procedural motion every part keeps (the motion budget), 0 = no budget (no-stretch first). */
    var MOTION_KEEP = (System.getProperty("rig.keep") ?: "0.95").toFloat()
    /**
     * Motion budget. [joints]/[weights] hold the no-tear weighting (after the contact pass), fullJ/fullW the full-motion
     * one. For each part ([Pose3D.groups]) the RMS motion of its surface (vertices it dominates in the full weighting)
     * over the [checkFrames] poses is compared with full motion; where it fell under [keep], that part's vertices blend
     * back toward the full-motion weights (the least blend that gets it to [keep], by bisection). The blend amount is
     * then spread smoothly over the surface (never lowered) so no new hard border appears. Returns a report.
     */
    private fun keepMotion(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, fullJ: IntArray, fullW: FloatArray,
                           baseJ: IntArray, baseW: FloatArray, sk: Skeleton, h: Float, jaw: Int, keep: Float): String {
        val t0 = System.nanoTime()
        val es = EdgeScan(p, V, idx, fullJ, fullW, h, jaw); val rep = es.rep; val first = es.first; val nb = es.nb
        val frames = checkFrames(sk, 0.4f); val J = sk.size
        val pose = Pose3D(sk, emptyList())
        val safeJ = joints.copyOf(); val safeW = weights.copyOf()
        fun dom(i: Int): Int { var b = 0; for (k in 1..3) if (baseW[i * 4 + k] > baseW[i * 4 + b]) b = k; return baseJ[i * 4 + b] }
        val mix = FloatArray(J)
        /** t 0..1: safe → full (seams blended), 1..2: full → base (the pre-v5 weighting); into oj/ow (top 4) */
        fun blend(i: Int, t: Float, oj: IntArray, ow: FloatArray, o: Int) {
            java.util.Arrays.fill(mix, 0f)
            if (t <= 1f) for (k in 0..3) { mix[safeJ[i * 4 + k]] += safeW[i * 4 + k] * (1 - t); mix[fullJ[i * 4 + k]] += fullW[i * 4 + k] * t }
            else { val u = t - 1f; for (k in 0..3) { mix[fullJ[i * 4 + k]] += fullW[i * 4 + k] * (1 - u); mix[baseJ[i * 4 + k]] += baseW[i * 4 + k] * u } }
            var tot = 0f
            for (k in 0..3) { var bj = -1; var bv = 0f; for (j in 0 until J) if (mix[j] > bv) { bv = mix[j]; bj = j }
                if (bj < 0) { oj[o + k] = 0; ow[o + k] = 0f } else { oj[o + k] = bj; ow[o + k] = bv; tot += bv; mix[bj] = 0f } }
            if (tot > 0f) for (k in 0..3) ow[o + k] /= tot }
        fun rms(verts: IntArray, wj: IntArray, ww: FloatArray): Float { var best = 0.0
            for (m in frames) { var sum = 0.0
                for ((n, i) in verts.withIndex()) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; var ox = 0f; var oy = 0f; var oz = 0f
                    for (k in 0..3) { val w = ww[n * 4 + k]; if (w == 0f) continue; val bb = wj[n * 4 + k] * 16
                        ox += w * (m[bb] * x + m[bb + 4] * y + m[bb + 8] * z + m[bb + 12]); oy += w * (m[bb + 1] * x + m[bb + 5] * y + m[bb + 9] * z + m[bb + 13]); oz += w * (m[bb + 2] * x + m[bb + 6] * y + m[bb + 10] * z + m[bb + 14]) }
                    sum += ((ox - x) * (ox - x) + (oy - y) * (oy - y) + (oz - z) * (oz - z)).toDouble() }
                best = maxOf(best, sum / verts.size.coerceAtLeast(1)) }
            return sqrt(best).toFloat() }
        val tRaw = FloatArray(V); val rep2 = StringBuilder()
        for (g in pose.groups) { val gs = g.toSet()
            val all = es.allVerts.filter { !es.isJaw[it] && dom(it) in gs }; if (all.size < 20) continue
            val step = maxOf(1, all.size / 3000); val vs = all.filterIndexed { n, _ -> n % step == 0 }.toIntArray()
            val fj = IntArray(vs.size * 4) { baseJ[vs[it / 4] * 4 + it % 4] }; val fw = FloatArray(vs.size * 4) { baseW[vs[it / 4] * 4 + it % 4] }
            val ref = rms(vs, fj, fw); if (ref < h * 1e-4f) continue
            val bj = IntArray(vs.size * 4); val bw = FloatArray(vs.size * 4)
            fun amp(t: Float): Float { for ((n, i) in vs.withIndex()) blend(i, t, bj, bw, n * 4); return rms(vs, bj, bw) / ref }
            // aim a little above [keep]: the check here samples fewer poses and vertices than the clips really show
            val goal = if (keep > 0f) minOf(1f, keep + 0.03f) else 0f
            val a0 = amp(0f); var t = 0f
            if (a0 < goal) { var lo = 0f; var hi = if (amp(1f) >= goal) 1f else 2f; repeat(10) { val mid = (lo + hi) / 2; if (amp(mid) >= goal) hi = mid else lo = mid }; t = hi }
            for (i in all) tRaw[i] = t
            rep2.append(g.joinToString("/") { sk.names[it] }).append(" ").append("%.0f→%.0f%%".format(a0 * 100, amp(t) * 100)).append(if (t > 0f) " (blend %.2f)".format(t) else "").append(", ") }
        // spread the blend over the surface (only upward), so neighbours of a released part don't get a hard border
        var tt = tRaw.copyOf()
        repeat(40) { val nx = tt.copyOf()
            for (a in es.allVerts) { val n = first[a + 1] - first[a]; if (n == 0) continue; var s = 0f; for (e in first[a] until first[a + 1]) s += tt[nb[e]]
                nx[a] = maxOf(tRaw[a], s / n) }
            tt = nx }
        val oj = IntArray(4); val ow = FloatArray(4); var moved = 0
        for (i in es.allVerts) { if (es.isJaw[i]) continue; val t = tt[i]; if (t <= 0f) continue
            blend(i, t, oj, ow, 0); for (k in 0..3) { joints[i * 4 + k] = oj[k]; weights[i * 4 + k] = ow[k] }; moved++ }
        for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
        val out = "motion budget ${(keep * 100).toInt()} %: " + rep2.toString().trimEnd(',', ' ') + "; $moved vertices given motion back, ${(System.nanoTime() - t0) / 1_000_000} ms"
        if (System.getProperty("rig.debug") != null || DEBUG_RELAX) println(out)
        return out
    }

    @Volatile var lastMotion = ""
    /** Motion scale floor: a part may be toned down to this share of its procedural motion, no further. */
    const val MOTION_MIN = 0.1f
    /**
     * Tones down the procedural motion ([Pose3D.groups], left / right together) of parts whose motion still tears the
     * mesh after the weight passes: greedily, the group whose reduction removes the most excess stretch (summed over
     * the worst edges and their worst frames) is scaled by 0.8, until every edge stays within [limit] or nothing helps.
     * The result is [Skeleton.motion] (saved in the .glb). Jaw edges are skipped: the mouth is meant to open.
     */
    private fun limitMotion(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int, limit: Float = 1.12f) {
        val t0 = System.nanoTime()
        val mot = FloatArray(sk.size) { 1f }; sk.motion = mot
        val groups = Pose3D(sk, emptyList()).groups
        val es = EdgeScan(p, V, idx, joints, weights, h, jaw); val ea = es.ea; val eb = es.eb; val E = es.E
        var worst = FloatArray(E); val wf = IntArray(E)
        fun fullScan(): Int { worst = es.scan(checkFrames(sk), wf = wf); return (0 until E).count { worst[it] > limit } }
        val initial = fullScan(); var nBad = initial; var iters = 0
        while (nBad > 0 && iters < 60) {
            // the worst edges and the frames they peak in
            val cand = (0 until E).filter { worst[it] > 1f + (limit - 1f) * 0.6f }.sortedByDescending { worst[it] }.take(4000).toIntArray()
            val cv = HashSet<Int>(); for (k in cand) { cv += ea[k]; cv += eb[k] }; val cvA = cv.toIntArray()
            val fcount = HashMap<Int, Int>(); for (k in cand) if (worst[k] > limit) fcount.merge(wf[k], 1, Int::plus)
            val nFrames = checkFrames(sk).size
            val fidx = (fcount.entries.sortedByDescending { it.value }.take(32).map { it.key } + (0 until nFrames step 6)).distinct()
            fun excess(): Double { val frames = checkFrames(sk); val wc = es.scan(fidx.map { frames[it] }, cand, cvA)
                var x = 0.0; for (r in wc) if (r > limit) x += (r - limit).toDouble(); return x }
            // several rounds on this candidate set, then a full check
            var moved = false
            repeat(5) {
                val base = excess(); if (base <= 0.0) return@repeat
                var bestG = -1; var bestGain = 0.0
                for ((g, js) in groups.withIndex()) { if (mot[js[0]] <= MOTION_MIN) continue
                    val keep = js.map { mot[it] }; for (j in js) mot[j] = maxOf(MOTION_MIN, mot[j] * 0.8f)
                    val gain = base - excess(); js.forEachIndexed { k, j -> mot[j] = keep[k] }
                    if (gain > bestGain + 1e-9) { bestGain = gain; bestG = g } }
                if (bestG < 0) return@repeat
                for (j in groups[bestG]) mot[j] = maxOf(MOTION_MIN, mot[j] * 0.8f)
                iters++; moved = true
            }
            if (!moved) break
            nBad = fullScan()
        }
        lastMotion = "motion limits: $initial edges over ${"%.2f".format(limit)} at full motion, $nBad after; " +
            groups.filter { mot[it[0]] < 1f }.joinToString { g -> g.joinToString("/") { sk.names[it] } + "=%.2f".format(mot[g[0]]) }.ifEmpty { "all parts at full motion" } +
            ", ${(System.nanoTime() - t0) / 1_000_000} ms"
        if (mot.all { it >= 1f }) sk.motion = null
        if (System.getProperty("rig.debug") != null || DEBUG_RELAX) println(lastMotion)
    }

    /**
     * Where the lower jaw is. Looks at the face from the side along the middle line: the forward extent and the
     * top/bottom silhouette per depth slice. A snout or muzzle shows up as a long front part much thinner than the
     * head; then the mouth line runs along the muzzle (40 % up from its underside) back to a hinge behind where the
     * muzzle starts, and the lower jaw is everything under that line in front of the hinge. A flat (human-like) face
     * gets a level mouth line just under the nose and a hinge at the middle of the head (by the ears).
     * The jaw rotates around the hinge, so the whole lower jaw swings open instead of the muzzle tip bending.
     */
    class Jaw(val pivot: FloatArray, val snout: Boolean, private val hingeZ: Float, private val ramp: Float, private val frontZ: Float,
              private val z0: Float, private val dz: Float, private val lineY: FloatArray, private val cx: Float, private val halfW: Float, private val bottomY: Float) {
        fun mouthY(z: Float): Float { val k = ((z - z0) / dz).coerceIn(0f, (lineY.size - 1).toFloat()); val a = k.toInt(); val b = minOf(a + 1, lineY.size - 1); val f = k - a
            return lineY[a] * (1 - f) + lineY[b] * f }
        fun weight(x: Float, y: Float, z: Float): Float {
            if (z <= hingeZ || z > frontZ || y < bottomY || abs(x - cx) > halfW) return 0f
            if (y >= mouthY(z)) return 0f
            return ((z - hingeZ) / ramp).coerceIn(0f, 1f)
        }
    }

    fun jawGeometry(p: FloatArray, V: Int, headC: FloatArray, headSpan: Float, headR: Float, neckY: Float, mouth: FloatArray, h: Float, nrm: FloatArray? = null): Jaw {
        val hx = headC[0]; val tipZ = mouth[2] + h * 0.01f
        val yLo = neckY - headSpan * 0.25f; val yHi = neckY + headSpan * 0.6f
        val NZ = 32; val z0 = headC[2]; val dz = ((tipZ - z0) / (NZ - 1)).coerceAtLeast(1e-6f)
        val top = FloatArray(NZ) { -Float.MAX_VALUE }; val bot = FloatArray(NZ) { Float.MAX_VALUE }; val wid = FloatArray(NZ)
        val ys = Array(NZ) { ArrayList<Long>() }   // y with the normal's up/down sign packed in
        var backZ = z0
        for (i in 0 until V) { val y = p[i * 3 + 1]; if (y < neckY || y > yHi || abs(p[i * 3] - hx) > headR * 0.3f) continue; backZ = minOf(backZ, p[i * 3 + 2]) }
        for (i in 0 until V) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            if (y < yLo || y > yHi || z < z0 || z > tipZ) continue
            val k = ((z - z0) / dz).toInt().coerceIn(0, NZ - 1)
            wid[k] = maxOf(wid[k], abs(x - hx))
            if (abs(x - hx) > headR * 0.6f) continue
            if (abs(x - hx) <= headR * 0.3f) { top[k] = maxOf(top[k], y); bot[k] = minOf(bot[k], y) }
            val up = if (nrm == null) 0 else if (nrm[i * 3 + 1] > 0.2f) 1 else if (nrm[i * 3 + 1] < -0.2f) 2 else 0
            ys[k] += (java.lang.Float.floatToIntBits(y + 1e3f).toLong() shl 2) or up.toLong() }
        // fill empty slices from the neighbour behind
        for (k in 1 until NZ) if (top[k] < bot[k]) { top[k] = top[k - 1]; bot[k] = bot[k - 1] }
        val thick = FloatArray(NZ) { if (top[it] >= bot[it]) top[it] - bot[it] else 0f }
        val ref = thick.take(4).filter { it > 0 }.maxOrNull() ?: 0f
        // muzzle starts at the first slice from which the profile stays thinner than 70 % of the head's
        var start = NZ - 1
        for (k in NZ - 1 downTo 0) { if (thick[k] < ref * 0.7f) start = k else break }
        val startZ = z0 + start * dz; val snoutLen = tipZ - startZ
        // a muzzle makes the head reach clearly further forward than backward (a round or human head is about even),
        // and its front part is thinner than the head
        val snout = ref > 0f && tipZ - z0 > (z0 - backZ) * 1.18f && snoutLen > (tipZ - z0) * 0.2f
        val lineY = FloatArray(NZ)
        val hingeZ: Float
        if (snout) {
            // a modelled mouth (lips apart, or a mouth slit) is an empty band between the upper and lower jaw:
            // the widest vertical gap in the lower 75 % of each muzzle slice. Without one, 40 % up from the underside.
            val gapY = FloatArray(NZ) { Float.NaN }
            // the gap must be outside the head: its floor faces up (top of the lower jaw) and its ceiling faces down
            // (underside of the upper jaw); a hollow inside the muzzle has it the other way round
            for (k in start until NZ) { val raw = ys[k]; if (raw.size < 6) continue; raw.sort()
                val l = FloatArray(raw.size) { java.lang.Float.intBitsToFloat((raw[it] shr 2).toInt()) - 1e3f }; val dir = IntArray(raw.size) { (raw[it] and 3).toInt() }
                val th = top[k] - bot[k]; var best = 0f
                for (q in 1 until l.size) { val g = l[q] - l[q - 1]; val mid = (l[q] + l[q - 1]) / 2
                    if (nrm != null && (dir[q - 1] == 2 || dir[q] == 1)) continue
                    if (mid < bot[k] + th * 0.75f && mid > bot[k] + th * 0.05f && g > best) { best = g; gapY[k] = mid } }
                if (best < maxOf(th * 0.06f, h * 0.002f)) gapY[k] = Float.NaN }
            val gaps = (start until NZ).count { !gapY[it].isNaN() }
            if (System.getProperty("rig.debug") != null) println("jaw gaps $gaps/${NZ - start}: ${gapY.joinToString { "%.3f".format(it) }}")
            for (k in 0 until NZ) { val kk = maxOf(k, start); lineY[k] = bot[kk] + 0.4f * (top[kk] - bot[kk]) }
            if (gaps >= (NZ - start) / 3) {
                // a mouth line is close to straight: robust (Theil–Sen) line through the gap centres, so a stray gap
                // inside the muzzle or between teeth doesn't bend it
                val ks = (start until NZ).filter { !gapY[it].isNaN() }
                val slopes = ArrayList<Float>(); for (a in ks.indices) for (b in a + 1 until ks.size) slopes += (gapY[ks[b]] - gapY[ks[a]]) / (ks[b] - ks[a])
                slopes.sort(); val sl = slopes[slopes.size / 2]
                val icp = ks.map { gapY[it] - sl * it }.sorted().let { it[it.size / 2] }
                for (k in 0 until NZ) lineY[k] = icp + sl * maxOf(k, start)
            }
            // smooth the line a little (scales, teeth and lips make the silhouette ragged)
            val sm = lineY.copyOf(); for (k in 1 until NZ - 1) sm[k] = (lineY[k - 1] + lineY[k] * 2 + lineY[k + 1]) / 4; sm.copyInto(lineY)
            hingeZ = startZ - maxOf(snoutLen * 0.3f, (startZ - z0) * 0.5f)
        } else {
            lineY.fill(mouth[1]); hingeZ = z0
        }
        val hingeY = lineY[maxOf(0, start.coerceAtMost(NZ - 1))].let { if (snout) it + (top[start] - bot[start]) * 0.15f else mouth[1] + h * 0.02f }
        val ramp = maxOf((tipZ - hingeZ) * 0.25f, h * 0.005f)
        // jaw width: the face's width around the mouth, a little wider so the cheeks' lower edge comes along
        val halfW = (if (snout) wid.slice(start until NZ).maxOrNull() ?: headR else headR) * 1.1f
        val bottomY = if (snout) (bot.slice(start until NZ).minOrNull() ?: neckY) - h * 0.02f else neckY - h * 0.04f
        if (System.getProperty("rig.debug") != null) println("jaw profile back=$backZ fb=${(tipZ - z0) / (z0 - backZ)} span=$headSpan headR=$headR z0=$z0 tip=$tipZ ref=$ref start=$start len=$snoutLen line=${lineY.joinToString { "%.3f".format(it) }} thick=${thick.joinToString { "%.3f".format(it) }} bot=${bot.joinToString { "%.3f".format(it) }}")
        return Jaw(floatArrayOf(hx, hingeY, hingeZ), snout, hingeZ, ramp, tipZ + h * 0.05f, z0, dz, lineY, hx, halfW, maxOf(bottomY, neckY - headSpan * 0.25f))
    }

    /**
     * Weight transfer + smoothing. Vertices are welded by position (UV seams split them; the halves must bend the
     * same or the seam cracks open). Separate pieces (clothing, armour, hair, accessories modelled as their own
     * shells) copy the weights of the nearest point of the main body underneath, so clothes follow the body.
     * Then two passes of neighbour averaging over the welded surface blend bone borders (a skirt or a sleeve
     * stretches instead of tearing). Bones in [keep] (jaw, wings, tail, ears) keep their own hard edges.
     */
    /** Welds vertices by position: rep[i] = the first vertex at the same place (UV seams split vertices). */
    private fun weld(p: FloatArray, V: Int, h: Float): IntArray {
        val q = (h * 2e-4f).coerceAtLeast(1e-7f)
        val rep = IntArray(V); val map = HashMap<Long, Int>(V * 2)
        for (i in 0 until V) { val key = ((Math.round(p[i * 3] / q).toLong() and 0x1FFFFF) shl 42) or ((Math.round(p[i * 3 + 1] / q).toLong() and 0x1FFFFF) shl 21) or (Math.round(p[i * 3 + 2] / q).toLong() and 0x1FFFFF)
            rep[i] = map.getOrPut(key) { i } }
        return rep
    }

    /** Edges between welded vertices, CSR: neighbours of a are second[first[a] until first[a + 1]]. */
    private fun adjacency(rep: IntArray, idx: IntArray, V: Int): Pair<IntArray, IntArray> {
        val deg = IntArray(V + 1)
        for (t in 0 until idx.size / 3) for (k in 0..2) { deg[rep[idx[t * 3 + k]]] += 2 }
        val first = IntArray(V + 1); for (i in 0 until V) first[i + 1] = first[i] + deg[i]
        val fill = first.copyOf(); val nb = IntArray(first[V])
        for (t in 0 until idx.size / 3) { val a = rep[idx[t * 3]]; val b = rep[idx[t * 3 + 1]]; val c = rep[idx[t * 3 + 2]]
            nb[fill[a]++] = b; nb[fill[a]++] = c; nb[fill[b]++] = a; nb[fill[b]++] = c; nb[fill[c]++] = a; nb[fill[c]++] = b }
        return first to nb
    }

    private fun transferAndSmooth(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, keep: BooleanArray, h: Float) {
        if (V == 0 || idx.isEmpty()) return
        val rep = weld(p, V, h)
        // connected pieces over the welded surface
        val uf = IntArray(V) { it }
        fun f(a: Int): Int { var x = a; while (uf[x] != x) { uf[x] = uf[uf[x]]; x = uf[x] }; return x }
        for (t in 0 until idx.size / 3) { val a = f(rep[idx[t * 3]]); val b = f(rep[idx[t * 3 + 1]]); val c = f(rep[idx[t * 3 + 2]]); if (a != b) uf[a] = b; val b2 = f(b); if (f(c) != b2) uf[f(c)] = b2 }
        val size = IntArray(V); for (i in 0 until V) if (rep[i] == i) size[f(i)]++
        var body = 0; for (i in 0 until V) if (size[i] > size[body]) body = i
        fun dominant(i: Int): Int { var b = 0; for (k in 1..3) if (weights[i * 4 + k] > weights[i * 4 + b]) b = k; return joints[i * 4 + b] }
        // 1) transfer onto loose pieces from the nearest body vertex (uniform grid over the body)
        val cell = h / 48f
        fun ck(x: Float, y: Float, z: Float) = ((Math.floor((x / cell).toDouble()).toLong() and 0xFFFFF) shl 40) or ((Math.floor((y / cell).toDouble()).toLong() and 0xFFFFF) shl 20) or (Math.floor((z / cell).toDouble()).toLong() and 0xFFFFF)
        val grid = HashMap<Long, IntList>()
        var loose = 0
        for (i in 0 until V) if (rep[i] == i && f(i) == body) grid.getOrPut(ck(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])) { IntList(8) }.add(i)
        if (grid.isNotEmpty()) for (i in 0 until V) {
            if (rep[i] != i) continue
            val c = f(i); if (c == body || size[c] > size[body] / 2) continue
            if (keep[dominant(i)]) continue
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            val gx = Math.floor((x / cell).toDouble()).toLong(); val gy = Math.floor((y / cell).toDouble()).toLong(); val gz = Math.floor((z / cell).toDouble()).toLong()
            var best = -1; var bd = Float.MAX_VALUE
            for (r in 1..3) {
                for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
                    val l = grid[(((gx + dx) and 0xFFFFF) shl 40) or (((gy + dy) and 0xFFFFF) shl 20) or ((gz + dz) and 0xFFFFF)] ?: continue
                    for (k in 0 until l.size) { val j = l[k]; val ddx = p[j * 3] - x; val ddy = p[j * 3 + 1] - y; val ddz = p[j * 3 + 2] - z
                        val d = ddx * ddx + ddy * ddy + ddz * ddz; if (d < bd) { bd = d; best = j } } }
                if (best >= 0) break
            }
            if (best < 0 || keep[dominant(best)]) continue
            for (k in 0..3) { joints[i * 4 + k] = joints[best * 4 + k]; weights[i * 4 + k] = weights[best * 4 + k] }
            loose++
        }
        grid.clear()
        // 2) smoothing over welded neighbours (weights live on the representative vertex)
        val S = 8
        for (pass in 0 until 2) {
            val aj = IntArray(V * S) { -1 }; val aw = FloatArray(V * S); val cnt = IntArray(V)
            fun add(dst: Int, src: Int) {
                cnt[dst]++
                for (k in 0..3) { val w = weights[src * 4 + k]; if (w <= 0f) continue; val j = joints[src * 4 + k]
                    var s = 0; while (s < S && aj[dst * S + s] != j && aj[dst * S + s] != -1) s++
                    if (s == S) continue
                    aj[dst * S + s] = j; aw[dst * S + s] += w } }
            for (t in 0 until idx.size / 3) { val a = rep[idx[t * 3]]; val b = rep[idx[t * 3 + 1]]; val c = rep[idx[t * 3 + 2]]
                add(a, b); add(a, c); add(b, a); add(b, c); add(c, a); add(c, b) }
            for (i in 0 until V) {
                if (rep[i] != i || cnt[i] == 0 || keep[dominant(i)]) continue
                // own weight counts as much as all neighbours together
                val m = HashMap<Int, Float>(8)
                for (k in 0..3) { val w = weights[i * 4 + k]; if (w > 0f) m.merge(joints[i * 4 + k], w * 0.5f, Float::plus) }
                for (s2 in 0 until S) { val j = aj[i * S + s2]; if (j < 0) break; m.merge(j, aw[i * S + s2] / cnt[i] * 0.5f, Float::plus) }
                val top = m.entries.sortedByDescending { it.value }.take(4); val tot = top.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1e-9f)
                for (k in 0..3) { val e = top.getOrNull(k); joints[i * 4 + k] = e?.key ?: 0; weights[i * 4 + k] = (e?.value ?: 0f) / tot }
            }
        }
        // seam halves get exactly the representative's weights
        for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
        if (System.getProperty("rig.debug") != null) println("rig: ${size.count { it > 0 }} pieces, body ${size[body]} welded vertices, $loose loose vertices took body weights")
    }

    /** Frames the no-tear pass checks: every procedural clip (idle, listening, thinking, talking with gestures at full
     *  loudness) sampled over a few cycles. */
    fun checkFrames(sk: Skeleton, step: Float = 0.2f): List<FloatArray> {
        val pose = Pose3D(sk, emptyList()); val out = ArrayList<FloatArray>()
        for (state in 0..4) { var t = 0f; while (t < 8f) { val o = FloatArray(sk.size * 16)
            // talking twice: at full loudness, and with a changing level (the bounce and nod follow it)
            val level = when (state) { 3 -> 1f; 4 -> (0.5f + 0.5f * kotlin.math.sin(t * 11f)).coerceIn(0f, 1f); else -> 0f }
            pose.evaluate(t, minOf(state, 3), level, o); out += o; t += step } }
        return out
    }

    /**
     * No-tear pass. Skins the mesh in every [checkFrames] pose and finds edges that stretch or shrink by more than
     * [limit] (see [stretch]): a hard weight border between two bones that move differently, e.g. breast / dress,
     * a hand resting on the thigh in a fused mesh, a wing touching the head or tail, head / neck. Around such edges the
     * weights are blended toward a spatially smoothed weight field (cell averages over a grid of size ρ, trilinearly
     * interpolated, so it is smooth and also covers separate shells like a dress lying over the body), fading out
     * smoothly away from the problem. ρ grows (1 % → 25 % of the height) until no edge is left. Jaw vertices are
     * left alone (the mouth is meant to open).
     */
    private fun relaxStretch(p: FloatArray, V: Int, idx: IntArray, joints: IntArray, weights: FloatArray, sk: Skeleton, h: Float, jaw: Int, limit: Float = 1.10f, focus: BooleanArray? = null, focusBones: IntArray = IntArray(0), forbid: Set<Int> = emptySet(), forbidAt: BooleanArray? = null, maxRho: Float = MAX_RHO, edgeOk: ((Int, Int) -> Boolean)? = null, maxRounds: Int = 60) {
        if (V == 0 || idx.isEmpty()) return
        val t0 = System.nanoTime()
        val rep = weld(p, V, h); val (first, nb) = adjacency(rep, idx, V)
        val all = checkFrames(sk)
        // bone hops in the skeleton: a vertex only blends in bones next to the ones it already has (chest ↔ breast,
        // neck, spine, shoulder), never something that is merely close in space (head over the chest, a hand on a thigh)
        val J = sk.size; val hop = Array(J) { IntArray(J) { 99 } }
        for (a in 0 until J) { hop[a][a] = 0; val pa = sk.parent[a]; if (pa >= 0) { hop[a][pa] = 1; hop[pa][a] = 1 } }
        for (k in 0 until J) for (a in 0 until J) for (b in 0 until J) if (hop[a][k] + hop[k][b] < hop[a][b]) hop[a][b] = hop[a][k] + hop[k][b]
        val isJaw = BooleanArray(V) { i -> (0..3).any { joints[i * 4 + it] == jaw && weights[i * 4 + it] > 0f } }
        fun len(a: Int, b: Int): Float { val dx = p[a * 3] - p[b * 3]; val dy = p[a * 3 + 1] - p[b * 3 + 1]; val dz = p[a * 3 + 2] - p[b * 3 + 2]; return sqrt(dx * dx + dy * dy + dz * dz) }
        // the focus region (plus anything that picked up breast weight) grows by a few rings so its border is checked too
        val ea = IntList(V * 3); val eb = IntList(V * 3)
        fun buildEdges() {
            ea.size = 0; eb.size = 0
            val inFocus = focus?.let { f0 -> val fz = BooleanArray(V) { f0[rep[it]] || f0[it] || (0..3).any { k -> weights[it * 4 + k] > 0f && joints[it * 4 + k] in focusBones } }
                repeat(3) { val nx = fz.copyOf(); for (a in 0 until V) if (fz[a] && rep[a] == a) for (e in first[a] until first[a + 1]) nx[nb[e]] = true; nx.copyInto(fz) }; fz }
            for (a in 0 until V) { if (rep[a] != a || isJaw[a]) continue
                if (inFocus != null && !inFocus[a]) continue
                for (e in first[a] until first[a + 1]) { val b = nb[e]; if (b > a && !isJaw[b] && len(a, b) > h * 1e-4f && (edgeOk == null || edgeOk(a, b))) { ea.add(a); eb.add(b) } } }
        }
        buildEdges()
        var E = ea.size; var rest = FloatArray(E) { len(ea[it], eb[it]) }
        val q = FloatArray(V * 3)
        /** Bad edges over [frames]: marks their vertices, returns the count; worstFrame gets the frame of each. */
        fun scan(frames: List<FloatArray>, bad: BooleanArray, worstFrame: IntArray?): Int {
            val worst = FloatArray(E); val wf = IntArray(E)
            for ((fi, m) in frames.withIndex()) {
                for (i in 0 until V) { if (rep[i] != i) continue; val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]; var ox = 0f; var oy = 0f; var oz = 0f
                    for (k in 0..3) { val w = weights[i * 4 + k]; if (w == 0f) continue; val bb = joints[i * 4 + k] * 16
                        ox += w * (m[bb] * x + m[bb + 4] * y + m[bb + 8] * z + m[bb + 12]); oy += w * (m[bb + 1] * x + m[bb + 5] * y + m[bb + 9] * z + m[bb + 13]); oz += w * (m[bb + 2] * x + m[bb + 6] * y + m[bb + 10] * z + m[bb + 14]) }
                    q[i * 3] = ox; q[i * 3 + 1] = oy; q[i * 3 + 2] = oz }
                for (k in 0 until E) { val a = ea[k]; val b = eb[k]; val dx = q[a * 3] - q[b * 3]; val dy = q[a * 3 + 1] - q[b * 3 + 1]; val dz = q[a * 3 + 2] - q[b * 3 + 2]
                    val r = stretch(rest[k], sqrt(dx * dx + dy * dy + dz * dz), h); if (r > worst[k]) { worst[k] = r; wf[k] = fi } }
            }
            java.util.Arrays.fill(bad, false); var n = 0
            for (k in 0 until E) if (worst[k] > limit) { bad[ea[k]] = true; bad[eb[k]] = true; n++; worstFrame?.let { it[n - 1] = wf[k] } }
            return n
        }
        val bad = BooleanArray(V); var wfr = IntArray(E)
        var nBad = scan(all, bad, wfr); val initial = nBad
        val crit = ArrayList<FloatArray>(); val critIdx = HashSet<Int>()
        fun addCrit(n: Int) { val cnt = HashMap<Int, Int>(); for (k in 0 until n) cnt.merge(wfr[k], 1, Int::plus)
            for (f in cnt.entries.sortedByDescending { it.value }.take(24).map { it.key }) if (critIdx.add(f)) crit += all[f] }
        addCrit(nBad)
        var rho = h * 0.01f; var rounds = 0; var atScale = 0
        // the best state seen (fewest bad edges over all frames); a pass that stops improving goes back to it
        var best = nBad; val bestJ = joints.copyOf(); val bestW = weights.copyOf(); var stale = 0
        while (nBad > 0 && rounds < maxRounds && stale < 10) {
            rounds++
            blendToField(p, V, rep, joints, weights, isJaw, bad, rho, hop, forbidAt, forbid, focusBones)
            nBad = scan(crit, bad, wfr)
            if (nBad == 0 || rounds % 5 == 0) { buildEdges(); E = ea.size; rest = FloatArray(E) { len(ea[it], eb[it]) }; wfr = IntArray(E)
                nBad = scan(all, bad, wfr); addCrit(nBad)   // confirm on every frame
                if (nBad < best) { best = nBad; joints.copyInto(bestJ); weights.copyInto(bestW); stale = 0 } else stale += 5 }
            if (nBad > 0 && ++atScale >= 3) { rho = minOf(rho * 2f, h * maxRho); atScale = 0 }
            if (DEBUG_RELAX) println("relax $rounds: rho=${"%.3f".format(rho / h)}h bad $nBad ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        if (nBad > best) { bestJ.copyInto(joints); bestW.copyInto(weights); nBad = best }
        for (i in 0 until V) { val r = rep[i]; if (r != i) for (k in 0..3) { joints[i * 4 + k] = joints[r * 4 + k]; weights[i * 4 + k] = weights[r * 4 + k] } }
        lastRelax = "no-tear pass: $initial edges over ${"%.2f".format(limit)} at start, $nBad after $rounds rounds (smoothing up to ${"%.3f".format(rho / h)} h), ${all.size} frames, ${(System.nanoTime() - t0) / 1_000_000} ms"
        if (System.getProperty("rig.debug") != null || DEBUG_RELAX) println(lastRelax)
    }

    /** Blends weights near [bad] vertices toward a smooth field: per-cell weight averages (cell size rho), trilinearly
     *  interpolated; the blend factor is itself a trilinear field (1 in cells with a bad vertex, fading over two cells). */
    private fun blendToField(p: FloatArray, V: Int, rep: IntArray, joints: IntArray, weights: FloatArray, isJaw: BooleanArray, bad: BooleanArray, rho: Float, hop: Array<IntArray>, focus: BooleanArray? = null, forbid: Set<Int> = emptySet(), noNew: IntArray = IntArray(0)) {
        // allocation-free: primitive cell maps and per-bone arrays. Android's runtime doesn't remove boxed Long / Int /
        // Float keys the way a desktop JVM does, so the old HashMap version made millions of objects per round and took
        // minutes on a phone (first-launch import stuck at "Rigging bones"). Same arithmetic, same order.
        fun c(v: Float) = Math.floor((v / rho).toDouble()).toInt()
        fun key(x: Int, y: Int, z: Int) = ((x.toLong() and 0xFFFFF) shl 40) or ((y.toLong() and 0xFFFFF) shl 20) or (z.toLong() and 0xFFFFF)
        // blend factor per cell: 1 where bad, dilated with falloff
        val alpha = CellMap(1024); val alphaV = FloatList()
        for (i in 0 until V) if (bad[i]) { val x = c(p[i * 3]); val y = c(p[i * 3 + 1]); val z = c(p[i * 3 + 2])
            for (dx in -2..2) for (dy in -2..2) for (dz in -2..2) { val d = maxOf(abs(dx), abs(dy), abs(dz)); val a = if (d <= 1) 1f else 0.4f
                val k = key(x + dx, y + dy, z + dz); val slot = alpha.get(k)
                if (slot < 0) { alpha.put(k, alphaV.size); alphaV.add(a) } else if (a > alphaV[slot]) alphaV[slot] = a } }
        if (alpha.size == 0) return
        // cell averages, only for cells near the problem: up to 12 bones per cell
        val acc = CellMap(1024); val accJ = IntList(1024); val accW = FloatList(); val accN = IntList(128)
        for (i in 0 until V) { if (rep[i] != i || isJaw[i]) continue; val k = key(c(p[i * 3]), c(p[i * 3 + 1]), c(p[i * 3 + 2]))
            if (alpha.get(k) < 0) continue
            var ci = acc.get(k); if (ci < 0) { ci = accN.size; acc.put(k, ci); accN.add(0); repeat(12) { accJ.add(-1); accW.add(0f) } }
            accN[ci] = accN[ci] + 1; val o = ci * 12
            for (s in 0..3) { val w = weights[i * 4 + s]; if (w <= 0f) continue; val j = joints[i * 4 + s]
                var t = 0; while (t < 12 && accJ[o + t] != j && accJ[o + t] != -1) t++; if (t == 12) continue; accJ[o + t] = j; accW[o + t] = accW[o + t] + w } }
        val J = hop.size
        val m = FloatArray(J); val mHas = BooleanArray(J); val mix = FloatArray(J); val mixHas = BooleanArray(J)
        val nj = IntArray(V * 4); val nw = FloatArray(V * 4); val changed = BooleanArray(V)
        val topJ = IntArray(4); val topW = FloatArray(4)
        for (i in 0 until V) {
            if (rep[i] != i || isJaw[i]) continue
            // trilinear position among cell centres
            val gx = p[i * 3] / rho - 0.5f; val gy = p[i * 3 + 1] / rho - 0.5f; val gz = p[i * 3 + 2] / rho - 0.5f
            val x0 = Math.floor(gx.toDouble()).toInt(); val y0 = Math.floor(gy.toDouble()).toInt(); val z0 = Math.floor(gz.toDouble()).toInt()
            val fx = gx - x0; val fy = gy - y0; val fz = gz - z0
            var al = 0f; var aw = 0f
            java.util.Arrays.fill(m, 0f); java.util.Arrays.fill(mHas, false); var tw = 0f
            for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
                val tri = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy) * (if (dz == 0) 1 - fz else fz)
                val k = key(x0 + dx, y0 + dy, z0 + dz)
                val sa = alpha.get(k); al += tri * (if (sa >= 0) alphaV[sa] else 0f); aw += tri
                val ci = acc.get(k); if (ci < 0) continue; val n = accN[ci]; if (n == 0) continue; val o = ci * 12
                for (t in 0 until 12) { val j = accJ[o + t]; if (j < 0) break; m[j] = (if (mHas[j]) m[j] else 0f) + tri * accW[o + t] / n; mHas[j] = true }; tw += tri
            }
            val alp = if (aw > 0f) al / aw else 0f
            if (alp <= 0f || tw <= 0f) continue
            // only bones one hop from the vertex's own (bones in index order, as the old small HashMap iterated)
            var kept = 0f
            for (j in 0 until J) { if (!mHas[j]) continue; var ok = false
                if (focus != null && focus[i] && j in forbid) { mHas[j] = false; continue }
                // a breast bone only spreads within its own breast's surface (never across the cleavage to the other side)
                if (j in noNew && (0..3).none { joints[i * 4 + it] == j && weights[i * 4 + it] > 0f }) { mHas[j] = false; continue }
                for (s in 0..3) if (weights[i * 4 + s] > 0.05f && hop[joints[i * 4 + s]][j] <= 1) { ok = true; break }
                if (!ok) mHas[j] = false else kept += m[j] }
            if (kept <= 0f) continue; tw = kept
            // blend own weights (1 - alp) with the field (alp)
            java.util.Arrays.fill(mixHas, false)
            for (s in 0..3) { val w = weights[i * 4 + s]; if (w > 0f) { val j = joints[i * 4 + s]; mix[j] = (if (mixHas[j]) mix[j] else 0f) + w * (1 - alp); mixHas[j] = true } }
            for (j in 0 until J) if (mHas[j]) { mix[j] = (if (mixHas[j]) mix[j] else 0f) + m[j] / tw * alp; mixHas[j] = true }
            // the 4 largest, ties to the lower bone index (a stable sort of the bones in index order)
            var nTop = 0
            for (j in 0 until J) { if (!mixHas[j]) continue; val w = mix[j]
                var pos = nTop; while (pos > 0 && topW[pos - 1] < w) pos--
                if (pos >= 4) continue
                val end = minOf(nTop, 3); var q = end; while (q > pos) { topJ[q] = topJ[q - 1]; topW[q] = topW[q - 1]; q-- }
                topJ[pos] = j; topW[pos] = w; if (nTop < 4) nTop++ }
            var tot = 0.0; for (t in 0 until nTop) tot += topW[t].toDouble(); val totF = tot.toFloat().coerceAtLeast(1e-9f)
            for (s in 0..3) { nj[i * 4 + s] = if (s < nTop) topJ[s] else 0; nw[i * 4 + s] = (if (s < nTop) topW[s] else 0f) / totF }
            changed[i] = true
        }
        for (i in 0 until V) if (changed[i]) for (s in 0..3) { joints[i * 4 + s] = nj[i * 4 + s]; weights[i * 4 + s] = nw[i * 4 + s] }
    }

    /** Bone → weight sums for one vertex without boxing; the 4 largest go back into joints / weights. */
    private class BoneMix(J: Int) {
        val acc = FloatArray(J); val has = BooleanArray(J); private val touched = IntArray(J); private var nt = 0
        private val tj = IntArray(4); private val tw = FloatArray(4)
        fun reset() { for (t in 0 until nt) has[touched[t]] = false; nt = 0 }
        fun add(j: Int, w: Float) { if (has[j]) acc[j] += w else { has[j] = true; acc[j] = w; touched[nt++] = j } }
        /** Positive entries, largest first (ties to the lower bone), normalised; false (nothing written) when they sum to 0 and [skipIfZero]. */
        fun writeTop4(i: Int, joints: IntArray, weights: FloatArray, skipIfZero: Boolean = false): Boolean {
            var n = 0
            for (j in acc.indices) { if (!has[j]) continue; val w = acc[j]; if (w <= 0f) continue
                var pos = n; while (pos > 0 && tw[pos - 1] < w) pos--
                if (pos >= 4) continue
                var q = minOf(n, 3); while (q > pos) { tj[q] = tj[q - 1]; tw[q] = tw[q - 1]; q-- }
                tj[pos] = j; tw[pos] = w; if (n < 4) n++ }
            var tot = 0.0; for (t in 0 until n) tot += tw[t].toDouble(); val totF = tot.toFloat()
            if (skipIfZero && totF <= 0f) return false
            for (k in 0..3) { joints[i * 4 + k] = if (k < n) tj[k] else 0; weights[i * 4 + k] = (if (k < n) tw[k] else 0f) / totF }
            return true
        }
    }

    /** Distances for a bounded multi-source Dijkstra over the vertices: an array reset only where it was touched. */
    private class Dijkstra(V: Int) {
        private val dist = FloatArray(V) { Float.MAX_VALUE }; private val touched = IntList(1024); private val heap = LongHeap()
        fun reset() { for (t in 0 until touched.size) dist[touched[t]] = Float.MAX_VALUE; touched.size = 0; heap.clear() }
        fun get(v: Int) = dist[v]
        fun set(v: Int, d: Float) { if (dist[v] == Float.MAX_VALUE) touched.add(v); dist[v] = d }
        fun push(d: Float, v: Int) = heap.add(LongHeap.key(d, v))
        fun isNotEmpty() = heap.size > 0
        fun pop() = heap.poll()
    }

    /** Min-heap of longs (distance bits << 32 | vertex: the same order as a PriorityQueue<Long> of those keys). */
    private class LongHeap {
        private var a = LongArray(1024); var size = 0; private set
        fun clear() { size = 0 }
        fun add(x: Long) { if (size == a.size) a = a.copyOf(size * 2); var i = size++; a[i] = x
            while (i > 0) { val pnt = (i - 1) / 2; if (a[pnt] <= a[i]) break; val t = a[pnt]; a[pnt] = a[i]; a[i] = t; i = pnt } }
        fun poll(): Long { val top = a[0]; a[0] = a[--size]; var i = 0
            while (true) { val l = 2 * i + 1; if (l >= size) break; var c = l; if (l + 1 < size && a[l + 1] < a[l]) c = l + 1
                if (a[i] <= a[c]) break; val t = a[i]; a[i] = a[c]; a[c] = t; i = c }
            return top }
        companion object {
            fun key(d: Float, v: Int) = (java.lang.Float.floatToIntBits(d).toLong() shl 32) or v.toLong()
            fun dist(k: Long) = java.lang.Float.intBitsToFloat((k ushr 32).toInt())
            fun vert(k: Long) = (k and 0xffffffffL).toInt()
        }
    }

    /** Open-addressing Long → Int map (−1 = absent) for grid cells, without boxing. */
    private class CellMap(cap0: Int) {
        private var keys = LongArray(Integer.highestOneBit(maxOf(16, cap0) * 2 - 1)); private var vals = IntArray(keys.size); private var used = BooleanArray(keys.size)
        var size = 0; private set
        private fun slot(k: Long, ks: LongArray, us: BooleanArray): Int { var h = (k * -0x61c8864680b583ebL ushr 32).toInt() and (ks.size - 1)
            while (us[h] && ks[h] != k) h = (h + 1) and (ks.size - 1); return h }
        fun get(k: Long): Int { val h = slot(k, keys, used); return if (used[h]) vals[h] else -1 }
        fun put(k: Long, v: Int) {
            if ((size + 1) * 2 > keys.size) { val ok = keys; val ov = vals; val ou = used
                keys = LongArray(ok.size * 2); vals = IntArray(ok.size * 2); used = BooleanArray(ok.size * 2)
                for (i in ok.indices) if (ou[i]) { val h = slot(ok[i], keys, used); keys[h] = ok[i]; vals[h] = ov[i]; used[h] = true } }
            val h = slot(k, keys, used); if (!used[h]) { used[h] = true; keys[h] = k; size++ }; vals[h] = v }
    }
    private class FloatList { private var a = FloatArray(256); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]; operator fun set(i: Int, v: Float) { a[i] = v } }
    @Volatile var lastRelax = ""
    /** Joints / weights of the last [rig] before the no-tear passes (tests: effective motion amplitude per part). */
    @Volatile var lastBaseWeights: Pair<IntArray, FloatArray>? = null
    var DEBUG_RELAX = false
    var RELAX = true
    var MAX_RHO = 0.04f
    /** v6 whole-body seam pass: the smoothing may grow to this share of the height (wider bands for full motion). */
    var BODY_RHO = 0.12f
    /** v6 seam pass: widest patch size (share of the height). */
    var SEAM_RHO = 0.08f
    /** v6 contact pass: longest fade along the surface (share of the height). */
    var FOLLOW_REACH = 0.3f
    /** Contact pass: longest fade toward the shared bone (share of the height). */
    var CONTACT_ROUNDS = (System.getProperty("rig.rounds") ?: "5").toInt()
    var ANCHOR_REACH = (System.getProperty("rig.reach") ?: "0.35").toFloat()
    /** Floor for the stretch measure: an edge shorter than this (0.5 % of the height, a few mm on a person) is judged
     *  by how much its length changes relative to this length, so sub-pixel slivers in scanned / generated meshes
     *  don't count as tears while anything visible does. */
    const val STRETCH_FLOOR = 0.005f
    /** Stretch of one edge: 1 + |length change| / max(rest length, floor). 1.15 = 15 % longer or shorter. */
    fun stretch(rest: Float, now: Float, h: Float): Float = 1f + abs(now - rest) / maxOf(rest, h * STRETCH_FLOOR)

    private fun segDist(x: Float, y: Float, z: Float, a: FloatArray, b: FloatArray): Float {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]; val l2 = dx * dx + dy * dy + dz * dz
        val t = if (l2 < 1e-12f) 0f else (((x - a[0]) * dx + (y - a[1]) * dy + (z - a[2]) * dz) / l2).coerceIn(0f, 1f)
        val px = a[0] + t * dx - x; val py = a[1] + t * dy - y; val pz = a[2] + t * dz - z
        return sqrt(px * px + py * py + pz * pz)
    }
}

/**
 * Evaluates a skeleton pose each frame: the file's own animation (looped) if it has one, plus procedural
 * idle/listen/think/talk motion on top (breathing, sway, nods, jaw from the lip-sync level, small arm gestures).
 * Writes column-major joint matrices (global × inverse bind) into [out].
 */
class Pose3D(private val sk: Skeleton, private val anims: List<Anim>) {
    companion object { /** largest breast / jiggle swing in radians */ const val BOUNCE_MAX = 0.03f
        /** share of a head turn / nod done at the neck joint (v6) */
        @JvmStatic var HEAD_SPLIT = (System.getProperty("rig.headsplit") ?: "0.5").toFloat() }
    private val head = sk.find("head"); private val neck = sk.find("neck"); private val jaw = sk.find("jaw", "mouth", "chin")
    private val chest = sk.find("chest", "spine2", "upperchest", "spine.003", "spine1"); private val spine = sk.find("spine", "hips")
    private val armL = sk.find("upperarm.l", "upper_arm.l", "leftarm", "shoulder.l", "arm.l", "l_upperarm"); private val armR = sk.find("upperarm.r", "upper_arm.r", "rightarm", "shoulder.r", "arm.r", "r_upperarm")
    private val wingL = sk.find("wing.l", "wing_l", "l_wing", "leftwing"); private val wingR = sk.find("wing.r", "wing_r", "r_wing", "rightwing")
    private val wingTipL = sk.find("wingtip.l"); private val wingTipR = sk.find("wingtip.r")
    private val tail = intArrayOf(sk.find("tail1", "tail", "tail.001", "tail_01"), sk.find("tail2", "tail.002", "tail_02"), sk.find("tail3", "tail.003", "tail_03"),
        sk.find("tail4", "tail.004", "tail_04"), sk.find("tail5", "tail.005", "tail_05")).filter { it >= 0 }.distinct().toIntArray()
    private val breastL = sk.find("breast.l", "breast_l", "l_breast", "boob.l"); private val breastR = sk.find("breast.r", "breast_r", "r_breast", "boob.r")
    private val earL = sk.find("ear.l", "ear_l", "l_ear"); private val earR = sk.find("ear.r", "ear_r", "r_ear")
    private val thighL = sk.find("thigh.l", "upperleg.l", "leftupleg", "l_thigh"); private val thighR = sk.find("thigh.r", "upperleg.r", "rightupleg", "r_thigh")
    private val idle = anims.firstOrNull { it.name.contains("idle", true) } ?: anims.firstOrNull()
    private val T = FloatArray(sk.size * 3); private val R = FloatArray(sk.size * 4); private val S = FloatArray(sk.size * 3)
    private val local = FloatArray(16); private val global = FloatArray(sk.size * 16); private val tmp = FloatArray(16)
    val hasJaw get() = jaw >= 0
    /** Joints the procedural motion moves, in groups that share one motion scale (left / right together, so the
     *  pose stays symmetric); the jaw is not here (lip-sync is never toned down). */
    val groups: List<IntArray> get() = listOf(intArrayOf(spine), intArrayOf(chest), intArrayOf(if (head >= 0) head else neck), intArrayOf(armL, armR),
        intArrayOf(wingL, wingR), intArrayOf(wingTipL, wingTipR), intArrayOf(breastL, breastR), intArrayOf(earL, earR), intArrayOf(thighL, thighR)).plus(tail.map { intArrayOf(it) })
        .map { g -> g.filter { it >= 0 }.distinct().toIntArray() }.filter { it.isNotEmpty() }.distinctBy { it.toList() }
    val usesFileAnimation get() = idle != null

    /** state: 0 idle, 1 listening, 2 thinking, 3 speaking; level = lip-sync loudness 0..1 */
    fun evaluate(timeSec: Float, state: Int, level: Float, out: FloatArray) {
        sk.restT.copyInto(T); sk.restR.copyInto(R); sk.restS.copyInto(S)
        idle?.let { a -> val tt = if (a.duration > 0) timeSec % a.duration else 0f; for (c in a.channels) sample(c, tt) }
        val mot = sk.motion
        fun rot(j: Int, ax: Float, ay: Float, az: Float, rad: Float) { if (j < 0) return
            val a = if (mot != null && j != jaw) rad * mot[j] else rad
            val q = M4.qmul(floatArrayOf(R[j * 4], R[j * 4 + 1], R[j * 4 + 2], R[j * 4 + 3]), M4.quat(ax, ay, az, a)); q.copyInto(R, j * 4) }
        // v6: a head turn / nod bends the neck too (half at the neck joint, half at the head): the head ends up turned
        // just as far, but each joint bends half as sharply, so the throat and the back of the neck don't tear
        fun rotHead(ax: Float, ay: Float, az: Float, rad: Float) {
            if (head >= 0 && neck >= 0 && neck != head && HEAD_SPLIT > 0f) { rot(neck, ax, ay, az, rad * HEAD_SPLIT); rot(head, ax, ay, az, rad * (1 - HEAD_SPLIT)) }
            else rot(if (head >= 0) head else neck, ax, ay, az, rad) }
        val s = kotlin.math.sin(timeSec * 1.3f); val br = kotlin.math.sin(timeSec * 2.1f)
        if (idle == null) { rot(spine, 0f, 0f, 1f, 0.015f * s); rot(chest, 1f, 0f, 0f, 0.012f * br) }
        when (state) {
            1 -> rotHead(0f, 0f, 1f, 0.12f)                                   // listening: head tilt
            2 -> { rotHead(1f, 0f, 0f, -0.1f); rotHead(0f, 1f, 0f, 0.15f * kotlin.math.sin(timeSec * 0.7f)) }
            3 -> { rotHead(1f, 0f, 0f, 0.06f * kotlin.math.sin(timeSec * 5f) * level + (if (jaw < 0) 0.08f * level else 0f))
                   rot(armL, 0f, 0f, 1f, 0.05f * kotlin.math.sin(timeSec * 2.3f)); rot(armR, 0f, 0f, 1f, -0.05f * kotlin.math.sin(timeSec * 1.9f)) }
            else -> rotHead(0f, 1f, 0f, 0.05f * s)
        }
        rot(jaw, 1f, 0f, 0f, 0.35f * level)
        if (idle == null) {
            // wings: slow idle "breathing" fold, a bigger lift while talking / listening
            val flap = (if (state == 3) 0.10f + 0.06f * level else if (state == 1) 0.07f else 0.035f) * kotlin.math.sin(timeSec * (if (state == 3) 2.6f else 1.1f))
            rot(wingL, 0f, 0f, 1f, flap); rot(wingR, 0f, 0f, 1f, -flap)
            rot(wingTipL, 0f, 0f, 1f, flap * 0.8f); rot(wingTipR, 0f, 0f, 1f, -flap * 0.8f)
            // tail: a travelling sway down the chain
            for (k in tail.indices) rot(tail[k], 0f, 1f, 0f, (0.08f + 0.04f * k) * kotlin.math.sin(timeSec * 1.4f - k * 0.7f))
            // chest / breasts: follow the breathing, with a soft bounce while talking
            // capped (BOUNCE_MAX) and the same on both sides, so the cleavage / dress between them never shears
            val bounce = (0.018f * br + (if (state == 3) 0.02f * level * kotlin.math.sin(timeSec * 9f) else 0f)).coerceIn(-BOUNCE_MAX, BOUNCE_MAX)
            rot(breastL, 1f, 0f, 0f, -bounce); rot(breastR, 1f, 0f, 0f, -bounce)
            // ears: perk up while listening, a little twitch now and then
            val perk = (if (state == 1) 0.18f else 0f) + 0.05f * maxOf(0f, kotlin.math.sin(timeSec * 0.9f) - 0.85f) * 6f
            rot(earL, 0f, 0f, 1f, -perk); rot(earR, 0f, 0f, 1f, perk)
            // legs: a slight weight shift
            rot(thighL, 0f, 0f, 1f, 0.015f * s); rot(thighR, 0f, 0f, 1f, 0.015f * s)
        }
        for (j in 0 until sk.size) {
            M4.trs(T, j * 3, R, j * 4, S, j * 3, local)
            val base = if (sk.parent[j] >= 0) null else sk.rootBase[j]
            if (sk.parent[j] >= 0) { M4.mul(global.copyOfRange(sk.parent[j] * 16, sk.parent[j] * 16 + 16), local, tmp); tmp.copyInto(global, j * 16) }
            else if (base != null) { M4.mul(base, local, tmp); tmp.copyInto(global, j * 16) } else local.copyInto(global, j * 16)
        }
        for (j in 0 until sk.size) { M4.mul(global.copyOfRange(j * 16, j * 16 + 16), sk.inverseBind.copyOfRange(j * 16, j * 16 + 16), tmp); tmp.copyInto(out, j * 16) }
    }

    private fun sample(c: AnimChannel, t: Float) {
        val n = if (c.path == 1) 4 else 3; val ts = c.times; if (ts.isEmpty()) return
        var k = 0; while (k < ts.size - 1 && ts[k + 1] <= t) k++
        val k2 = minOf(k + 1, ts.size - 1); val f = if (c.step || k2 == k || ts[k2] <= ts[k]) 0f else ((t - ts[k]) / (ts[k2] - ts[k])).coerceIn(0f, 1f)
        val dst = when (c.path) { 0 -> T; 1 -> R; else -> S }; val o = c.joint * n
        if (c.values.size < (k2 + 1) * n) return
        if (c.path == 1) { // nlerp
            var dot = 0f; for (i in 0..3) dot += c.values[k * 4 + i] * c.values[k2 * 4 + i]; val sg = if (dot < 0) -1f else 1f
            var l = 0f; for (i in 0..3) { val v = c.values[k * 4 + i] * (1 - f) + sg * c.values[k2 * 4 + i] * f; dst[o + i] = v; l += v * v }
            val il = 1f / sqrt(l.coerceAtLeast(1e-12f)); for (i in 0..3) dst[o + i] *= il
        } else for (i in 0 until n) dst[o + i] = c.values[k * n + i] * (1 - f) + c.values[k2 * n + i] * f
    }
}
