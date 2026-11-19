package app.belle.assistant.mesh

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
 * Render sheets of the auto-rig on real avatars (bundled Boob Dragon and Bob, and an imported Pixal3D model):
 * the bone each vertex mostly follows (colour map, front + side), the joints, and the rig posed (jaw open, arms
 * raised, legs bent, wings/tail/ears moved, chest/breast motion) with the actual texture, CPU-skinned exactly like
 * the shader. Written to build/rig_sheet_<name>.png and a joint list to build/rig_<name>.txt.
 */
class RigSheetTest {
    private fun find(vararg names: String) = names.flatMap { n -> listOf(File("src/main/assets/default_avatars/$n"), File("app/src/main/assets/default_avatars/$n"), File("../art/$n"), File("art/$n")) }.firstOrNull { it.exists() }

    private class View(val side: Boolean, val S: Int, val b: FloatArray) {
        val h = maxOf(b[4] - b[1], b[3] - b[0], b[5] - b[2])
        fun sx(x: Float, z: Float) = ((if (side) -z else x) - (if (side) -(b[2] + b[5]) / 2 else (b[0] + b[3]) / 2)) / h * S * 0.92f + S / 2f
        fun sy(y: Float) = S - ((y - b[1]) / h * S * 0.92f + S * 0.04f)
        fun depth(x: Float, z: Float) = if (side) -x else z
    }

    /** Skin matrices for local rotations [rot] (bone index → quaternion applied on top of rest). */
    private fun skin(sk: Skeleton, rot: Map<Int, FloatArray>): FloatArray {
        val n = sk.size; val global = FloatArray(n * 16); val out = FloatArray(n * 16); val local = FloatArray(16); val tmp = FloatArray(16)
        val R = sk.restR.copyOf()
        for ((j, q) in rot) { val r0 = floatArrayOf(R[j * 4], R[j * 4 + 1], R[j * 4 + 2], R[j * 4 + 3]); M4.qmul(r0, q).copyInto(R, j * 4) }
        for (j in 0 until n) {
            M4.trs(sk.restT, j * 3, R, j * 4, sk.restS, j * 3, local)
            if (sk.parent[j] >= 0) { M4.mul(global.copyOfRange(sk.parent[j] * 16, sk.parent[j] * 16 + 16), local, tmp); tmp.copyInto(global, j * 16) }
            else (sk.rootBase[j]?.let { M4.mul(it, local, tmp); tmp } ?: local).copyInto(global, j * 16)
        }
        for (j in 0 until n) { M4.mul(global.copyOfRange(j * 16, j * 16 + 16), sk.inverseBind.copyOfRange(j * 16, j * 16 + 16), tmp); tmp.copyInto(out, j * 16) }
        return out
    }

    private fun skinned(m: Mesh3D, mats: FloatArray): FloatArray {
        val p = m.pos; val J = m.joints!!; val W = m.weights!!; val o = FloatArray(p.size)
        for (i in 0 until m.vertexCount) { val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            for (k in 0..3) { val w = W[i * 4 + k]; if (w == 0f) continue; val b = J[i * 4 + k] * 16
                o[i * 3] += w * (mats[b] * x + mats[b + 4] * y + mats[b + 8] * z + mats[b + 12])
                o[i * 3 + 1] += w * (mats[b + 1] * x + mats[b + 5] * y + mats[b + 9] * z + mats[b + 13])
                o[i * 3 + 2] += w * (mats[b + 2] * x + mats[b + 6] * y + mats[b + 10] * z + mats[b + 14]) } }
        return o
    }

    private val palette = intArrayOf(0x808080, 0x4060ff, 0x00c0ff, 0x00ffa0, 0xffff00, 0xff0000, 0xff60c0, 0xc040ff, 0x8000ff, 0xff9000, 0xffc080, 0xa05000,
        0x00ff00, 0x009000, 0x004000, 0x90ff90, 0x00ffff, 0x008080, 0xff00ff, 0x800080, 0xffffff, 0xffa0a0, 0xa0a0ff, 0x606000, 0x00a0a0, 0xa000a0, 0xa0a000, 0x404040)

    private fun draw(m: Mesh3D, pos: FloatArray, v: View, colorOf: (vert: Int, u: Float, vv: Float) -> Int): IntArray {
        val S = v.S; val img = IntArray(S * S) { 0xFF202028.toInt() }; val zb = FloatArray(S * S) { -1e9f }
        val idx = m.idx; val uv = m.uv
        for (t in 0 until idx.size / 3) { val a = idx[t * 3]; val c1 = idx[t * 3 + 1]; val c2 = idx[t * 3 + 2]
            val x0 = v.sx(pos[a * 3], pos[a * 3 + 2]); val y0 = v.sy(pos[a * 3 + 1]); val x1 = v.sx(pos[c1 * 3], pos[c1 * 3 + 2]); val y1 = v.sy(pos[c1 * 3 + 1]); val x2 = v.sx(pos[c2 * 3], pos[c2 * 3 + 2]); val y2 = v.sy(pos[c2 * 3 + 1])
            val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2); if (abs(den) < 1e-9f) continue
            // face normal from the skinned positions, two-sided lambert
            val ux = pos[c1 * 3] - pos[a * 3]; val uy = pos[c1 * 3 + 1] - pos[a * 3 + 1]; val uz = pos[c1 * 3 + 2] - pos[a * 3 + 2]
            val wx = pos[c2 * 3] - pos[a * 3]; val wy = pos[c2 * 3 + 1] - pos[a * 3 + 1]; val wz = pos[c2 * 3 + 2] - pos[a * 3 + 2]
            var nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            if (v.side) { val t2 = nx; nx = -nz; nz = t2 }
            val ln = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            val k = 0.35f + 0.75f * abs((nx * -0.3f + ny * 0.5f + nz * 0.81f) / ln)
            val d0 = v.depth(pos[a * 3], pos[a * 3 + 2]); val d1 = v.depth(pos[c1 * 3], pos[c1 * 3 + 2]); val d2 = v.depth(pos[c2 * 3], pos[c2 * 3 + 2])
            for (py in maxOf(0, minOf(y0, y1, y2).toInt())..minOf(S - 1, maxOf(y0, y1, y2).toInt()))
                for (px in maxOf(0, minOf(x0, x1, x2).toInt())..minOf(S - 1, maxOf(x0, x1, x2).toInt())) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den; val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den; val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * d0 + w1 * d1 + w2 * d2; val o = py * S + px; if (z <= zb[o]) continue
                    zb[o] = z
                    val vert = if (w0 >= w1 && w0 >= w2) a else if (w1 >= w2) c1 else c2
                    val u = if (uv != null) w0 * uv[a * 2] + w1 * uv[c1 * 2] + w2 * uv[c2 * 2] else 0f
                    val vv = if (uv != null) w0 * uv[a * 2 + 1] + w1 * uv[c1 * 2 + 1] + w2 * uv[c2 * 2 + 1] else 0f
                    val col = colorOf(vert, u, vv)
                    val r = (((col shr 16) and 255) * k).toInt().coerceIn(0, 255); val g = (((col shr 8) and 255) * k).toInt().coerceIn(0, 255); val bl = ((col and 255) * k).toInt().coerceIn(0, 255)
                    img[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                } }
        return img
    }

    private fun sheet(name: String, file: File) {
        val raw = Import3D.read(mapOf(file.name to file.readBytes())).withNormals()
        val pm = ProgressiveMesh.build(raw)
        val t0 = System.nanoTime()
        val r = AutoRig3D.rig(pm)
        val rigMs = (System.nanoTime() - t0) / 1_000_000
        val m = r.mesh; val sk = m.skeleton!!
        val tex = m.texture?.let { runCatching { ImageIO.read(it.inputStream()) }.getOrNull() }
        fun texCol(u: Float, v: Float): Int = tex?.getRGB(((u - kotlin.math.floor(u)) * tex.width).toInt().coerceIn(0, tex.width - 1), ((v - kotlin.math.floor(v)) * tex.height).toInt().coerceIn(0, tex.height - 1))?.and(0xFFFFFF) ?: 0xC0C0C0
        fun bone(n: String) = sk.names.indexOfFirst { it.equals(n, true) }
        fun q(ax: Float, ay: Float, az: Float, deg: Float) = M4.quat(ax, ay, az, Math.toRadians(deg.toDouble()).toFloat())
        val poses = linkedMapOf<String, Map<Int, FloatArray>>(
            "rest" to emptyMap(),
            "talk: jaw open, head tilt, ears perk" to listOfNotNull(bone("jaw").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, 22f) }, bone("head").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, 10f) },
                bone("ear.L").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, -25f) }, bone("ear.R").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, 25f) }).toMap(),
            "arms up" to listOfNotNull(bone("shoulder.L").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, 60f) }, bone("shoulder.R").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, -60f) },
                bone("elbow.L").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, 30f) }, bone("elbow.R").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, -30f) }).toMap(),
            "chest turn + breasts" to listOfNotNull(bone("chest").takeIf { it >= 0 }?.let { it to q(0f, 1f, 0f, 25f) }, bone("breast.L").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, -12f) },
                bone("breast.R").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, 12f) }).toMap(),
            "legs: step + knee bend" to listOfNotNull(bone("thigh.L").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, -35f) }, bone("shin.L").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, 45f) },
                bone("thigh.R").takeIf { it >= 0 }?.let { it to q(1f, 0f, 0f, 15f) }).toMap(),
            "wings + tail" to (listOfNotNull(bone("wing.L").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, 30f) }, bone("wing.R").takeIf { it >= 0 }?.let { it to q(0f, 0f, 1f, -30f) }) +
                sk.names.indices.filter { sk.names[it].startsWith("tail") }.map { it to q(0f, 1f, 0f, 18f) }).toMap(),
        )
        val S = 420
        val cols = poses.size + 1
        val out = BufferedImage(S * cols, S * 2 + 30, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics(); g.font = Font("SansSerif", Font.BOLD, 13)
        // column 0: dominant bone map (front, side) + joints
        val J = m.joints!!; val Wt = m.weights!!
        fun dom(i: Int): Int { var best = 0; for (k in 1..3) if (Wt[i * 4 + k] > Wt[i * 4 + best]) best = k; return J[i * 4 + best] }
        for ((row, side) in listOf(false, true).withIndex()) {
            val v = View(side, S, m.bounds())
            val img = draw(m, m.pos, v) { i, _, _ -> palette[dom(i) % palette.size] }
            out.setRGB(0, 30 + row * S, S, S, img, 0, S)
            g.color = Color.WHITE
            for (j in 0 until sk.size) { val x = r.jointsWorld.getOrNull(j * 3) ?: continue
                val wx = r.jointsWorld[j * 3]; val wy = r.jointsWorld[j * 3 + 1]; val wz = r.jointsWorld[j * 3 + 2]
                val px = v.sx(wx, wz).toInt(); val py = v.sy(wy).toInt() + 30 + row * S
                g.fillOval(px - 3, py - 3, 6, 6)
                if (!side) { g.font = Font("SansSerif", Font.PLAIN, 9); g.drawString(sk.names[j], px + 4, py) }
                if (x.isNaN()) continue }
            for ((ci, e) in poses.entries.withIndex()) {
                val pos = skinned(m, skin(sk, e.value))
                out.setRGB(S * (ci + 1), 30 + row * S, S, S, draw(m, pos, v) { _, u, vv -> texCol(u, vv) }, 0, S)
            }
        }
        g.font = Font("SansSerif", Font.BOLD, 13); g.color = Color.WHITE
        g.drawString("$name: bones (colour = bone each vertex follows most)", 4, 20)
        for ((ci, k) in poses.keys.withIndex()) g.drawString(k, S * (ci + 1) + 4, 20)
        g.dispose()
        File("build").mkdirs()
        ImageIO.write(out, "png", File("build/rig_sheet_$name.png"))
        val txt = StringBuilder("$name: ${m.vertexCount} vertices, ${m.triCount} triangles, rig v${AutoRig3D.VERSION} in $rigMs ms, ${sk.size} bones, jaw ${r.jawVertices} vertices, snout=${r.jaw?.snout}\n")
        val counts = IntArray(sk.size); for (i in 0 until m.vertexCount) counts[dom(i)]++
        for (j in 0 until sk.size) txt.append("  ${sk.names[j].padEnd(12)} parent=${sk.parent[j].let { if (it >= 0) sk.names[it] else "-" }.padEnd(12)} at ${r.jointsWorld.slice(j * 3 until j * 3 + 3).joinToString { "%.3f".format(it) }}  vertices=${counts[j]}\n")
        File("build/rig_$name.txt").writeText(txt.toString())
        println(txt)
    }

    @Test fun boobDragon() { sheet("boob_dragon", find("boob_dragon.glb")!!) }
    @Test fun bob() { sheet("bob", find("bob.glb")!!) }
    @Test fun importedPixal3d() { val f = find("dragoness_pixal3d.glb"); assumeTrue(f != null); sheet("import_dragoness_pixal3d", f!!) }
}
