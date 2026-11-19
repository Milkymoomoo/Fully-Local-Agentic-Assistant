package app.belle.assistant.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Normalised point (0..1 of image width / height). */
data class P(val x: Float, val y: Float)

/** Normalised rect for the mouth. */
data class MouthBox(val cx: Float, val cy: Float, val w: Float, val h: Float)

/** A 2D character rig: joints + mouth, all in normalised image coordinates. */
data class Rig2D(val joints: Map<String, P>, val mouth: MouthBox, val auto: Boolean = true) {
    fun j(n: String) = joints[n] ?: DRAGON.joints.getValue(n)
    fun has(n: String) = joints.containsKey(n)
    /** Optional full-body parts present in this rig (wings, tail, legs). */
    val extras: List<String> get() = EXTRA.filter { has(it) }
    fun toLine(): String = ((JOINTS + extras).joinToString(";") { n -> j(n).let { "$n=${it.x},${it.y}" } }) +
        ";mouth=${mouth.cx},${mouth.cy},${mouth.w},${mouth.h};auto=$auto"

    companion object {
        val JOINTS = listOf("headTop", "neck", "hips", "shoulderL", "elbowL", "wristL", "shoulderR", "elbowR", "wristR")
        /** Optional extra parts, used in pairs (root → tip) when present: wings, tail, legs (hip → knee → foot). */
        val EXTRA = listOf("wingRootL", "wingTipL", "wingRootR", "wingTipR", "tailBase", "tailTip",
            "hipL", "kneeL", "footL", "hipR", "kneeR", "footR")
        private val EXTRA_GROUPS = listOf(listOf("wingRootL", "wingTipL"), listOf("wingRootR", "wingTipR"), listOf("tailBase", "tailTip"),
            listOf("hipL", "kneeL", "footL"), listOf("hipR", "kneeR", "footR"))

        /** Hand-placed full-body rig for the bundled "Boob Dragon" picture (standing, wings, tail, legs; painted background). */
        val BOOB_DRAGON = Rig2D(mapOf(
            "headTop" to P(0.53f, 0.06f), "neck" to P(0.50f, 0.335f), "hips" to P(0.50f, 0.60f),
            "shoulderL" to P(0.38f, 0.38f), "elbowL" to P(0.345f, 0.50f), "wristL" to P(0.31f, 0.59f),
            "shoulderR" to P(0.62f, 0.38f), "elbowR" to P(0.645f, 0.48f), "wristR" to P(0.60f, 0.57f),
            "wingRootL" to P(0.40f, 0.34f), "wingTipL" to P(0.33f, 0.24f),
            "wingRootR" to P(0.62f, 0.33f), "wingTipR" to P(0.78f, 0.47f),
            "tailBase" to P(0.58f, 0.70f), "tailTip" to P(0.77f, 0.76f),
            "hipL" to P(0.45f, 0.66f), "kneeL" to P(0.45f, 0.80f), "footL" to P(0.44f, 0.93f),
            "hipR" to P(0.55f, 0.66f), "kneeR" to P(0.54f, 0.80f), "footR" to P(0.53f, 0.93f),
        ), MouthBox(0.44f, 0.275f, 0.07f, 0.012f), auto = false)

        /** Hand-placed rig for the bundled dragon avatar. */
        val DRAGON = Rig2D(mapOf(
            "headTop" to P(0.50f, 0.07f), "neck" to P(0.50f, 0.46f), "hips" to P(0.50f, 1.05f),
            "shoulderL" to P(0.27f, 0.54f), "elbowL" to P(0.21f, 0.78f), "wristL" to P(0.14f, 0.90f),
            "shoulderR" to P(0.73f, 0.54f), "elbowR" to P(0.79f, 0.78f), "wristR" to P(0.86f, 0.90f),
        ), MouthBox(0.50f, 0.39f, 0.12f, 0.026f), auto = false)

        fun parse(s: String?): Rig2D? = runCatching {
            if (s.isNullOrBlank()) return null
            val kv = s.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }
            val joints = JOINTS.associateWith { n -> kv.getValue(n).split(",").let { P(it[0].toFloat(), it[1].toFloat()) } }.toMutableMap()
            // optional parts only count when their whole group is there
            for (g in EXTRA_GROUPS) if (g.all { kv.containsKey(it) }) g.forEach { n -> joints[n] = kv.getValue(n).split(",").let { P(it[0].toFloat(), it[1].toFloat()) } }
            val m = kv.getValue("mouth").split(",").map { it.toFloat() }
            Rig2D(joints, MouthBox(m[0], m[1], m[2], m[3]), kv["auto"] != "false")
        }.getOrNull()

        /**
         * Heuristic auto-rig from an alpha mask (row-major, true = character pixel).
         * Neck = narrowest row between the head and the shoulders; mouth just above it.
         * [face] (optional) = eye midpoint + eye distance from a face detector, normalised.
         */
        fun auto(mask: BooleanArray?, w: Int, h: Int, face: Triple<Float, Float, Float>? = null): Rig2D {
            var top = 0f; var bottom = 1f; var left = 0f; var right = 1f
            var neckY = -1f; var neckCx = 0.5f
            if (mask != null && mask.count { it } > w * h / 50) {
                val widths = IntArray(h); val cxs = FloatArray(h)
                var t = -1; var b = -1; var l = w; var r = 0
                for (y in 0 until h) {
                    var a = -1; var z = -1
                    for (x in 0 until w) if (mask[y * w + x]) { if (a < 0) a = x; z = x }
                    if (a >= 0) { if (t < 0) t = y; b = y; l = min(l, a); r = max(r, z); widths[y] = z - a + 1; cxs[y] = (a + z) / 2f / w }
                }
                top = t / h.toFloat(); bottom = (b + 1) / h.toFloat(); left = l / w.toFloat(); right = (r + 1) / w.toFloat()
                val bh = b - t
                // widest row in the head zone, then narrowest row below it (the neck)
                val headEnd = t + (bh * 0.45).toInt()
                val widest = (t until max(t + 1, headEnd)).maxByOrNull { widths[it] } ?: t
                val lo = widest + 1; val hi = t + (bh * 0.75).toInt()
                if (hi > lo) {
                    val n = (lo until hi).minByOrNull { widths[it] }!!
                    if (widths[n] < widths[widest] * 0.85) { neckY = n / h.toFloat(); neckCx = cxs[n] }
                }
            }
            val bw = right - left; val bh = bottom - top
            var mouth: MouthBox
            if (face != null) {
                val (ex, ey, ed) = face
                mouth = MouthBox(ex, ey + ed * 1.05f, ed * 0.9f, ed * 0.22f)
                if (neckY < 0) { neckY = ey + ed * 2.0f; neckCx = ex }
                top = min(top, ey - ed * 1.6f)
            } else {
                if (neckY < 0) neckY = top + bh * 0.58f
                val mouthY = top + (neckY - top) * 0.86f
                mouth = MouthBox(neckCx, mouthY, bw * 0.15f, bh * 0.035f)
            }
            val shoulderY = neckY + bh * 0.08f
            val half = bw * 0.24f
            val armLen = bh * 0.2f
            val joints = mapOf(
                "headTop" to P(neckCx, top + bh * 0.05f), "neck" to P(neckCx, neckY), "hips" to P(neckCx, bottom + bh * 0.05f),
                "shoulderL" to P(neckCx - half, shoulderY), "elbowL" to P(neckCx - half * 1.4f, shoulderY + armLen),
                "wristL" to P(neckCx - half * 1.5f, shoulderY + armLen * 2), "shoulderR" to P(neckCx + half, shoulderY),
                "elbowR" to P(neckCx + half * 1.4f, shoulderY + armLen), "wristR" to P(neckCx + half * 1.5f, shoulderY + armLen * 2),
            )
            return Rig2D(joints, mouth, auto = true)
        }
    }
}

/** Animation pose; angles in radians. */
data class Pose2D(
    var sway: Float = 0f, var breathe: Float = 0f, var head: Float = 0f, var nod: Float = 0f,
    var armUL: Float = 0f, var armLL: Float = 0f, var armUR: Float = 0f, var armLR: Float = 0f, var jaw: Float = 0f,
    var wingL: Float = 0f, var wingR: Float = 0f, var tail: Float = 0f, var legL: Float = 0f, var legR: Float = 0f,
)

/**
 * Linear-blend skinning on a coarse grid (Live2D-style "mesh with bones").
 * Bones: spine (hips→neck), head (neck→top), upper/lower arms. The jaw opens by
 * pushing the region under the mouth line down. Opaque images get a pinned
 * border so the background doesn't swing with the body.
 */
class Skin2D(val rig: Rig2D, val w: Float, val h: Float, val cols: Int = 20, val rows: Int = 20, val opaque: Boolean = false) {
    private class Bone(val from: P, val to: P, val parent: Int, val kind: String = "")
    private val bones: List<Bone>
    val rest: FloatArray            // x,y pairs in pixels
    private val weights: Array<FloatArray>
    private val mats: Array<FloatArray>

    init {
        fun px(p: P) = P(p.x * w, p.y * h)
        val j = { n: String -> px(rig.j(n)) }
        bones = listOf(
            Bone(j("hips"), j("neck"), -1), Bone(j("neck"), j("headTop"), 0),
            Bone(j("shoulderL"), j("elbowL"), 0), Bone(j("elbowL"), j("wristL"), 2),
            Bone(j("shoulderR"), j("elbowR"), 0), Bone(j("elbowR"), j("wristR"), 4),
        ) + buildList {
            // optional full-body parts: wings and tail hang off the spine, legs off the hips
            if (rig.has("wingRootL")) add(Bone(j("wingRootL"), j("wingTipL"), 0, "wingL"))
            if (rig.has("wingRootR")) add(Bone(j("wingRootR"), j("wingTipR"), 0, "wingR"))
            if (rig.has("tailBase")) add(Bone(j("tailBase"), j("tailTip"), 0, "tail"))
            for (sd in listOf("L", "R")) if (rig.has("hip$sd")) {
                add(Bone(j("hip$sd"), j("knee$sd"), 0, "thigh$sd")); add(Bone(j("knee$sd"), j("foot$sd"), 6 + size, "shin$sd")) }
        }.let { extra ->
            // fix shin parents (index of its thigh in the final list)
            extra.mapIndexed { k, b -> if (b.kind.startsWith("shin")) Bone(b.from, b.to, 6 + k - 1, b.kind) else b }
        }
        mats = Array(bones.size + 1) { FloatArray(6) }
        val n = (cols + 1) * (rows + 1)
        rest = FloatArray(n * 2)
        for (r in 0..rows) for (c in 0..cols) { val i = r * (cols + 1) + c; rest[2 * i] = c * w / cols; rest[2 * i + 1] = r * h / rows }
        val scale = max(w, h)
        weights = Array(n) { i ->
            val x = rest[2 * i]; val y = rest[2 * i + 1]
            val ws = FloatArray(bones.size + 1)
            for ((b, bone) in bones.withIndex()) {
                var d = segDist(x, y, bone.from, bone.to) / scale
                // the head owns everything above the neck line, the body everything below
                if (b == 1 && y > bones[1].from.y) d += (y - bones[1].from.y) / scale * 1.5f
                if (b == 0 && y < bones[1].from.y) d += (bones[1].from.y - y) / scale * 1.5f
                ws[b] = 1f / (d * d * d * d + 1e-6f)
            }
            if (opaque) { val db = min(min(x, w - x), min(y, h - y)) / scale; ws[bones.size] = 1f / (db * db * db * db + 1e-6f) * 0.6f }
            val s = ws.sum(); for (k in ws.indices) ws[k] /= s
            ws
        }
    }

    /** Fills [out] with deformed vertex positions (same layout as [rest]). */
    fun deform(p: Pose2D, out: FloatArray) {
        val m = rig.mouth
        val jawTop = m.cy * h; val jawBottom = (m.cy + m.h * 4f) * h
        val jawHalf = m.w * 0.8f * w; val open = p.jaw.coerceIn(0f, 1f) * m.h * h * 1.4f
        // bone matrices: world = parent * rotate(pivot = bone.from)
        for ((b, bone) in bones.withIndex()) {
            val local = when (b) {
                0 -> mul(rot(bone.from, p.sway), scaleY(bone.from, 1f + p.breathe))
                1 -> mul(translate(0f, p.nod * h), rot(bone.from, p.head))
                2 -> rot(bone.from, p.armUL); 3 -> rot(bone.from, p.armLL)
                4 -> rot(bone.from, p.armUR); 5 -> rot(bone.from, p.armLR)
                else -> when (bone.kind) {
                    "wingL" -> rot(bone.from, p.wingL); "wingR" -> rot(bone.from, p.wingR); "tail" -> rot(bone.from, p.tail)
                    "thighL" -> rot(bone.from, p.legL); "thighR" -> rot(bone.from, p.legR); "shinL" -> rot(bone.from, -p.legL * 0.6f); "shinR" -> rot(bone.from, -p.legR * 0.6f)
                    else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
                }
            }
            mats[b] = if (bone.parent < 0) local else mul(mats[bone.parent], local)
        }
        mats[bones.size] = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        for (i in 0 until rest.size / 2) {
            var x = rest[2 * i]; var y = rest[2 * i + 1]
            if (open > 0 && y > jawTop && y < jawBottom + open) {
                val fx = (1f - abs(x - m.cx * w) / (jawHalf * 2)).coerceIn(0f, 1f)
                val fy = if (y < jawBottom) 1f else (1f - (y - jawBottom) / (open + 1f)).coerceIn(0f, 1f)
                y += open * smooth(fx) * fy
            }
            var ox = 0f; var oy = 0f; var sw = 0f
            val ws = weights[i]
            for (b in ws.indices) { val wgt = ws[b]; if (wgt < 1e-4f) continue
                val a = mats[b]; sw += wgt; ox += wgt * (a[0] * x + a[2] * y + a[4]); oy += wgt * (a[1] * x + a[3] * y + a[5]) }
            out[2 * i] = ox / sw; out[2 * i + 1] = oy / sw
        }
    }

    /** Where the (deformed) mouth line is, for drawing the mouth interior. Pixels. */
    fun mouthCenter(p: Pose2D): P {
        val m = rig.mouth; val x = m.cx * w; val y = m.cy * h
        val a = mats[1]
        return P(a[0] * x + a[2] * y + a[4], a[1] * x + a[3] * y + a[5])
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun segDist(x: Float, y: Float, a: P, b: P): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }
    // affine [a b c d tx ty]: x' = a x + c y + tx ; y' = b x + d y + ty
    private fun rot(p: P, ang: Float): FloatArray { val c = cos(ang); val s = sin(ang)
        return floatArrayOf(c, s, -s, c, p.x - c * p.x + s * p.y, p.y - s * p.x - c * p.y) }
    private fun scaleY(p: P, k: Float) = floatArrayOf(1f, 0f, 0f, k, 0f, p.y - k * p.y)
    private fun translate(x: Float, y: Float) = floatArrayOf(1f, 0f, 0f, 1f, x, y)
    private fun mul(m: FloatArray, n: FloatArray) = floatArrayOf(
        m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
        m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
        m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5])

    companion object { const val DEG = (PI / 180).toFloat() }
}

/**
 * Drives the pose over time from the assistant state and speech energy.
 * state: 0 idle, 1 listening, 2 thinking, 3 speaking.
 */
class PoseDriver {
    private var energy = 0f; private var nodT = -10f; private var quietFor = 0f; private var t = 0f
    private var headS = 0f; private var lean = 0f
    fun step(dt: Float, state: Int, amp: Float, out: Pose2D, gentle: Float = 1f) {
        t += dt
        energy += (amp - energy) * min(1f, dt * 12f)
        // sentence boundary ≈ a pause after speech → small nod
        if (state == 3) { if (amp < 0.05f) quietFor += dt else { if (quietFor > 0.25f) nodT = t; quietFor = 0f } }
        val targetHead = when (state) { 1 -> 7f; 2 -> -6f; else -> 0f } * Skin2D.DEG
        headS += (targetHead - headS) * min(1f, dt * 4f)
        lean += ((if (state == 1) 1f else 0f) - lean) * min(1f, dt * 3f)
        val k = gentle
        out.breathe = (0.012f * sin(t * 2 * PI.toFloat() * 0.25f)) * k
        out.sway = (1.2f * Skin2D.DEG * sin(t * 2 * PI.toFloat() * 0.11f) + lean * 1.5f * Skin2D.DEG) * k
        val talk = if (state == 3) energy else 0f
        val nodPulse = (t - nodT).let { if (it in 0f..0.5f) sin(it / 0.5f * PI.toFloat()) else 0f }
        out.head = (headS + 2f * Skin2D.DEG * sin(t * 2 * PI.toFloat() * 0.17f) + talk * 3f * Skin2D.DEG * sin(t * 7f)) * k
        out.nod = (nodPulse * 0.012f + talk * 0.004f * sin(t * 11f) + (if (state == 2) -0.006f else 0f)) * k
        val g = talk * 10f * Skin2D.DEG
        out.armUL = (g * sin(t * 2.3f) - 1f * Skin2D.DEG * sin(t * 0.7f)) * k
        out.armLL = (g * 1.4f * sin(t * 2.3f + 0.6f)) * k
        out.armUR = (-g * sin(t * 1.9f + 1f) + 1f * Skin2D.DEG * sin(t * 0.6f)) * k
        out.armLR = (-g * 1.4f * sin(t * 1.9f + 1.6f)) * k
        out.jaw = if (state == 3) (amp * 1.6f).coerceIn(0f, 1f) else 0f
        // full-body extras (used only when the rig has them): slow wing "breathing" that lifts while talking,
        // a lazy tail sway, a slight weight shift between the legs
        val flap = (2.5f + 6f * talk + 2f * lean) * Skin2D.DEG * sin(t * 2 * PI.toFloat() * (0.3f + 0.5f * talk))
        out.wingL = flap * k; out.wingR = -flap * k
        out.tail = (5f * Skin2D.DEG * sin(t * 2 * PI.toFloat() * 0.21f) + 2f * Skin2D.DEG * sin(t * 2 * PI.toFloat() * 0.53f)) * k
        val shift = 1.2f * Skin2D.DEG * sin(t * 2 * PI.toFloat() * 0.08f)
        out.legL = shift * k; out.legR = shift * k
    }
}
