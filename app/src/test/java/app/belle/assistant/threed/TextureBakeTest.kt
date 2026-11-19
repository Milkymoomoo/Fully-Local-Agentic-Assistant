package app.belle.assistant.threed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

class TextureBakeTest {
    /** UV sphere of radius r at the origin. */
    private fun sphere(r: Float, n: Int = 48): Pair<FloatArray, IntArray> {
        val p = ArrayList<Float>(); val t = ArrayList<Int>()
        for (i in 0..n) for (j in 0 until 2 * n) { val th = Math.PI * i / n; val ph = Math.PI * j / n
            p += (r * sin(th) * cos(ph)).toFloat(); p += (r * cos(th)).toFloat(); p += (r * sin(th) * sin(ph)).toFloat() }
        val w = 2 * n
        for (i in 0 until n) for (j in 0 until w) { val a = i * w + j; val b = i * w + (j + 1) % w; val c = a + w; val d = b + w
            t += listOf(a, c, b, b, c, d) }
        return p.toFloatArray() to t.toIntArray()
    }

    @Test fun frontTakesTheImageBackIsFilled() {
        val (pos, idx) = sphere(0.3f)
        // left half of the image red, right half blue
        val s = 256; val img = P3Math.Img(s, s, Array(3) { c -> FloatArray(s * s) { i -> val x = i % s; when (c) { 0 -> if (x < s / 2) 1f else 0f; 2 -> if (x < s / 2) 0f else 1f; else -> 0f } } })
        val r = TextureBake.bake(pos, idx, img, null, 45.0, zs = 256)
        val nv = pos.size / 3
        assertTrue("about half the sphere is seen (${r.seen}/$nv)", r.seen in nv / 5..nv * 2 / 3)
        assertEquals(nv, r.seen + r.filled + r.through)
        // a front vertex on the left (x<0, z>0) is red, on the right blue
        var okL = 0; var okR = 0; var nL = 0; var nR = 0
        for (i in 0 until nv) { val x = pos[i * 3]; val z = pos[i * 3 + 2]; if (z < 0.2f || kotlin.math.abs(x) < 0.05f) continue
            if (x < 0) { nL++; if (r.colors[i * 3] > 0.9f && r.colors[i * 3 + 2] < 0.1f) okL++ } else { nR++; if (r.colors[i * 3 + 2] > 0.9f && r.colors[i * 3] < 0.1f) okR++ } }
        assertTrue("left front red $okL/$nL", okL > nL * 0.95); assertTrue("right front blue $okR/$nR", okR > nR * 0.95)
        // every vertex has a colour (back filled from the front)
        for (i in 0 until nv) assertTrue(r.colors[i * 3] + r.colors[i * 3 + 2] > 0.5f)
    }

    @Test fun clusterKeepsAClosedSurface() {
        val (pos, idx) = sphere(0.3f, 96)
        val (p, _, t) = TextureBake.cluster(pos, null, idx, 64)
        assertTrue(p.size / 3 < pos.size / 3); assertTrue(t.size / 3 > 1000)
        for (v in t) assertTrue(v in 0 until p.size / 3)
    }

    /** The real decoded port mesh (box-only files): bake with the reference crop + mask, cluster, write proof files. */
    @Test fun realPortMesh() {
        val d = File("/workspace/port3d/ref"); val mf = File(d, "mesh512_port.f32")
        assumeTrue(mf.exists())
        fun f32(f: File) = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        fun i32(f: File) = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { b -> IntArray(b.remaining()).also { b.get(it) } }
        val pos = f32(mf); val idx = i32(File(d, "mesh512_port.i32"))
        val bi = ImageIO.read(File(d, "crop.png")); val s = bi.width
        val img = P3Math.Img(s, s, Array(3) { c -> FloatArray(s * s) { i -> ((bi.getRGB(i % s, i / s) shr (16 - 8 * c)) and 255) / 255f } })
        val mask = File(d, "mask1024.u8").readBytes().let { b -> FloatArray(b.size) { (b[it].toInt() and 255) / 255f } }
        val t0 = System.currentTimeMillis()
        val r = TextureBake.bake(pos, idx, img, mask, 46.82375797514575)
        val t1 = System.currentTimeMillis()
        val (cp, cc, ct) = TextureBake.cluster(pos, r.colors, idx, 256)
        val t2 = System.currentTimeMillis()
        val m = app.belle.assistant.mesh.Mesh3D(cp, null, null, cc, null, null, ct, null, null)
        File("build").mkdirs(); File("build/pixal3d_port.glb").writeBytes(app.belle.assistant.mesh.Glb.write(m))
        val txt = "verts ${pos.size / 3} tris ${idx.size / 3}: seen ${r.seen} through ${r.through} filled ${r.filled}, bake ${t1 - t0} ms; cluster 256 -> ${cp.size / 3} verts ${ct.size / 3} tris ${t2 - t1} ms"
        File("build/pixal3d_bake.txt").writeText(txt + "\n"); println(txt)
        // proof sheet: the crop | baked mesh from the camera | from behind | from the side (orthographic splats)
        val W = 512; val out = BufferedImage(W * 4, W, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().drawImage(bi.getScaledInstance(W, W, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        val nv = cp.size / 3
        for ((k, view) in listOf<(Int) -> Triple<Float, Float, Float>>(
            { i -> Triple(cp[i * 3], cp[i * 3 + 1], cp[i * 3 + 2]) },
            { i -> Triple(-cp[i * 3], cp[i * 3 + 1], -cp[i * 3 + 2]) },
            { i -> Triple(-cp[i * 3 + 2], cp[i * 3 + 1], cp[i * 3]) }).withIndex()) {
            val zb = FloatArray(W * W) { -9f }; val ox = W * (k + 1)
            for (i in 0 until nv) { val (x, y, z) = view(i); val px = ((x + 0.5f) * W).toInt(); val py = ((0.5f - y) * W).toInt()
                for (dy in 0..1) for (dx in 0..1) { val qx = px + dx; val qy = py + dy; if (qx !in 0 until W || qy !in 0 until W) continue
                    if (z > zb[qy * W + qx]) { zb[qy * W + qx] = z
                        val rgb = (((cc!![i * 3] * 255).toInt().coerceIn(0, 255)) shl 16) or (((cc[i * 3 + 1] * 255).toInt().coerceIn(0, 255)) shl 8) or ((cc[i * 3 + 2] * 255).toInt().coerceIn(0, 255))
                        out.setRGB(ox + qx, qy, rgb) } } }
        }
        ImageIO.write(out, "png", File("build/pixal3d_bake_sheet.png"))
        assertTrue(r.seen > pos.size / 3 / 10)
    }
}
