package app.belle.assistant.mesh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/** The auto-rig's lower jaw: snout detection on the bundled dragons, and closed/open renders of the head. */
class JawTest {
    private fun asset(n: String) = listOf(File("src/main/assets/default_avatars/$n"), File("app/src/main/assets/default_avatars/$n")).first { it.exists() }

    private fun skin(m: Mesh3D, bones: FloatArray): FloatArray {
        val out = FloatArray(m.pos.size)
        for (i in 0 until m.vertexCount) { val x = m.pos[i*3]; val y = m.pos[i*3+1]; val z = m.pos[i*3+2]
            for (k in 0..3) { val w = m.weights!![i*4+k]; if (w == 0f) continue; val j = m.joints!![i*4+k] * 16
                out[i*3] += w * (bones[j]*x + bones[j+4]*y + bones[j+8]*z + bones[j+12]); out[i*3+1] += w * (bones[j+1]*x + bones[j+5]*y + bones[j+9]*z + bones[j+13]); out[i*3+2] += w * (bones[j+2]*x + bones[j+6]*y + bones[j+10]*z + bones[j+14]) } }
        return out
    }

    /** Textured, lit z-buffer render of the head region, turned [yaw] radians, framed on [c] with half-size [r]. */
    private fun render(m: Mesh3D, pos: FloatArray, tex: BufferedImage, yaw: Float, c: FloatArray, r: Float, S: Int, jawTint: Boolean): BufferedImage {
        val img = BufferedImage(S, S, BufferedImage.TYPE_INT_RGB); val zb = FloatArray(S * S) { -1e9f }
        val g = img.createGraphics(); g.color = java.awt.Color(24, 24, 32); g.fillRect(0, 0, S, S)
        val cy = cos(yaw); val sy = sin(yaw)
        val X = FloatArray(m.vertexCount); val Y = FloatArray(m.vertexCount); val Z = FloatArray(m.vertexCount)
        for (i in 0 until m.vertexCount) { val x = pos[i*3] - c[0]; val y = pos[i*3+1] - c[1]; val z = pos[i*3+2] - c[2]
            val rx = x * cy + z * sy; val rz = -x * sy + z * cy
            X[i] = rx / r * S / 2 + S / 2; Y[i] = S / 2 - y / r * S / 2; Z[i] = rz }
        val uv = m.uv!!; val tw = tex.width; val th = tex.height; val idx = m.idx
        for (t in 0 until idx.size / 3) { val a = idx[t*3]; val b = idx[t*3+1]; val d = idx[t*3+2]
            val den = (Y[b] - Y[d]) * (X[a] - X[d]) + (X[d] - X[b]) * (Y[a] - Y[d]); if (kotlin.math.abs(den) < 1e-9f) continue
            // flat light from the face normal
            val ux = pos[b*3]-pos[a*3]; val uy = pos[b*3+1]-pos[a*3+1]; val uz = pos[b*3+2]-pos[a*3+2]
            val vx = pos[d*3]-pos[a*3]; val vy = pos[d*3+1]-pos[a*3+1]; val vz = pos[d*3+2]-pos[a*3+2]
            var nx = uy*vz-uz*vy; var ny = uz*vx-ux*vz; var nz = ux*vy-uy*vx; val nl = kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(1e-12f); nx /= nl; ny /= nl; nz /= nl
            val lk = 0.45f + 0.6f * kotlin.math.abs(nx * 0.3f * cy + ny * 0.6f + nz * 0.75f)
            val jaw = jawTint && m.joints!![a*4] == AutoRig3D.JAW
            for (py in maxOf(0, minOf(Y[a], Y[b], Y[d]).toInt())..minOf(S - 1, maxOf(Y[a], Y[b], Y[d]).toInt()))
                for (px in maxOf(0, minOf(X[a], X[b], X[d]).toInt())..minOf(S - 1, maxOf(X[a], X[b], X[d]).toInt())) {
                    val fx = px + .5f; val fy = py + .5f
                    val w0 = ((Y[b] - Y[d]) * (fx - X[d]) + (X[d] - X[b]) * (fy - Y[d])) / den; val w1 = ((Y[d] - Y[a]) * (fx - X[d]) + (X[a] - X[d]) * (fy - Y[d])) / den; val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * Z[a] + w1 * Z[b] + w2 * Z[d]; val o = py * S + px; if (z <= zb[o]) continue; zb[o] = z
                    val u = w0*uv[a*2]+w1*uv[b*2]+w2*uv[d*2]; val v = w0*uv[a*2+1]+w1*uv[b*2+1]+w2*uv[d*2+1]
                    val col = tex.getRGB(((u - kotlin.math.floor(u)) * tw).toInt().coerceIn(0, tw - 1), ((v - kotlin.math.floor(v)) * th).toInt().coerceIn(0, th - 1))
                    var rr = ((col shr 16) and 255) * lk; var gg = ((col shr 8) and 255) * lk; var bb = (col and 255) * lk
                    if (jaw) { rr = rr * 0.6f + 100; gg *= 0.6f; bb *= 0.6f }
                    img.setRGB(px, py, (rr.toInt().coerceIn(0, 255) shl 16) or (gg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255))
                } }
        return img
    }

    private fun sheet(name: String, file: String): AutoRig3D.Jaw {
        val src = Glb.read(asset(file).readBytes()).withNormals()
        val r = AutoRig3D.rig(src); val m = r.mesh; val sk = m.skeleton!!
        val b = m.bounds(); val h = b[4] - b[1]
        val head = r.jointsWorld.copyOfRange(AutoRig3D.HEAD * 3, AutoRig3D.HEAD * 3 + 3)
        val jaw = r.jointsWorld.copyOfRange(AutoRig3D.JAW * 3, AutoRig3D.JAW * 3 + 3)
        println("$name jaw verts=${r.jawVertices} hinge=${jaw.joinToString { "%.3f".format(it) }} mouth=${r.mouth.joinToString { "%.3f".format(it) }} head=${head.joinToString { "%.3f".format(it) }}")
        val tex = ImageIO.read(m.texture!!.inputStream())
        val pose = Pose3D(sk, m.animations); val bones = FloatArray(sk.size * 16)
        pose.evaluate(0f, 3, 0f, bones); val closed = skin(m, bones)
        pose.evaluate(0f, 3, 1f, bones); val open = skin(m, bones)
        val c = floatArrayOf(head[0], (jaw[1] + r.mouth[1]) / 2, (jaw[2] + r.mouth[2]) / 2); val rad = h * 0.16f
        val S = 360; val out = BufferedImage(S * 3, S * 2, BufferedImage.TYPE_INT_RGB); val g = out.createGraphics()
        for ((col, yaw) in listOf(0f, 0.8f, 1.5708f).withIndex()) {
            g.drawImage(render(m, closed, tex, yaw.toFloat(), c, rad, S, false), col * S, 0, null)
            g.drawImage(render(m, open, tex, yaw.toFloat(), c, rad, S, true), col * S, S, null)
        }
        g.color = java.awt.Color.WHITE; g.drawString("$name: closed (top), jaw open with lip-sync level 1 (bottom, jaw tinted red); front / 3-4 / side", 8, 14)
        File("build").mkdirs(); ImageIO.write(out, "png", File("build/jaw_$name.png"))
        // side profile of the middle strip: vertices coloured by normal (up = green, down = blue), the jaw line in white
        run { val P = 600; val im = BufferedImage(P, P, BufferedImage.TYPE_INT_RGB); val j = r.jaw!!
            val hx = head[0]; val z0 = c[2] - rad * 1.6f; val y0 = c[1] - rad * 1.6f; val sc = P / (rad * 3.2f)
            for (i in 0 until m.vertexCount) { if (kotlin.math.abs(m.pos[i*3] - hx) > h * 0.06f) continue
                val px = ((m.pos[i*3+2] - z0) * sc).toInt(); val py = P - ((m.pos[i*3+1] - y0) * sc).toInt(); if (px !in 0 until P || py !in 0 until P) continue
                val ny = m.nrm!![i*3+1]; im.setRGB(px, py, if (j.weight(m.pos[i*3], m.pos[i*3+1], m.pos[i*3+2]) > 0) 0xFF4040 else if (ny > 0.2f) 0x40FF40 else if (ny < -0.2f) 0x4080FF else 0x909090) }
            for (px in 0 until P) { val z = z0 + px / sc; val py = P - ((j.mouthY(z) - y0) * sc).toInt(); if (py in 0 until P) im.setRGB(px, py, 0xFFFFFF) }
            ImageIO.write(im, "png", File("build/jawprofile_$name.png")) }
        return r.jaw!!
    }

    @Test fun dragonsGetASnoutJaw() {
        for ((n, f) in listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb")) {
            val j = sheet(n, f)
            println("$n snout=${j.snout}")
            assertTrue("$n should be detected as a snout", j.snout)
        }
    }
}

class JawHumanTest {
    /** A round head (the synthetic bust) must not be mistaken for a muzzle. */
    @Test fun roundHeadIsNoSnout() {
        val P = ArrayList<Float>(); val I = ArrayList<Int>(); val U = ArrayList<Float>(); val seg = 32
        fun ell(cy: Float, rx: Float, ry: Float, rz: Float) { val base = P.size / 3
            for (i in 0..seg) for (j in 0..seg) { val th = Math.PI * i / seg; val ph = 2 * Math.PI * j / seg
                P += (rx * sin(th) * cos(ph)).toFloat(); P += (cy + ry * cos(th)).toFloat(); P += (rz * sin(th) * sin(ph)).toFloat(); U += j.toFloat() / seg; U += i.toFloat() / seg }
            for (i in 0 until seg) for (j in 0 until seg) { val a = base + i * (seg + 1) + j; val b = a + seg + 1; I += listOf(a, b, a + 1, a + 1, b, b + 1) } }
        ell(0.6f, 0.9f, 0.6f, 0.5f); ell(1.25f, 0.12f, 0.2f, 0.12f); ell(1.75f, 0.38f, 0.38f, 0.38f)
        val r = AutoRig3D.rig(Mesh3D(P.toFloatArray(), null, U.toFloatArray(), null, null, null, I.toIntArray(), null, null))
        assertTrue("round head taken for a snout", r.jaw?.snout == false)
        assertTrue(r.jawVertices > 0)
    }
}
