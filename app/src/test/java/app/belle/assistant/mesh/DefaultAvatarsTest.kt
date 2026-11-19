package app.belle.assistant.mesh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The two bundled default 3D avatars (assets/default_avatars): import + progressive mesh + auto-rig, with a bone-colour map. */
class DefaultAvatarsTest {
    private fun asset(n: String) = listOf(File("src/main/assets/default_avatars/$n"), File("app/src/main/assets/default_avatars/$n")).first { it.exists() }

    private fun run(name: String, file: String): AutoRig3D.Result {
        var t = System.nanoTime()
        val mesh = Glb.read(asset(file).readBytes()); val tRead = (System.nanoTime() - t) / 1e9
        t = System.nanoTime(); val pm = ProgressiveMesh.build(mesh); val tPm = (System.nanoTime() - t) / 1e9
        t = System.nanoTime(); val r = AutoRig3D.rig(pm); val tRig = (System.nanoTime() - t) / 1e9
        val sk = r.mesh.skeleton!!
        val count = IntArray(sk.size); for (i in 0 until r.mesh.vertexCount) count[r.mesh.joints!![i * 4]]++
        println("$name verts=${mesh.vertexCount} tris=${mesh.triCount} read=${"%.2f".format(tRead)}s pm=${"%.2f".format(tPm)}s rig=${"%.2f".format(tRig)}s jaw=${r.jawVertices}")
        for (j in 0 until sk.size) println("  ${sk.names[j].padEnd(11)} parent=${sk.parent[j].toString().padStart(2)} verts=${count[j].toString().padStart(6)} at ${r.jointsWorld.slice(j * 3 until j * 3 + 3).joinToString { "%.3f".format(it) }}")
        boneMap(r, File("build/rig_$name.png"))
        return r
    }

    /** Front (x,y) and side (z,y) scatter of the vertices coloured by their main bone, joints as white dots. */
    private fun boneMap(r: AutoRig3D.Result, out: File) {
        val m = r.mesh; val b = m.bounds(); val S = 520; val img = BufferedImage(S * 2, S, BufferedImage.TYPE_INT_RGB)
        val h = maxOf(b[4] - b[1], b[3] - b[0], b[5] - b[2]); val g = img.createGraphics(); g.color = java.awt.Color(20, 20, 28); g.fillRect(0, 0, S * 2, S)
        fun col(j: Int) = java.awt.Color.HSBtoRGB((j * 0.137f) % 1f, 0.75f, 1f)
        fun px(a: Float, lo: Float) = ((a - lo) / h * (S - 40) + 20).toInt()
        for (i in 0 until m.vertexCount step 2) { val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]; val c = col(m.joints!![i * 4])
            val fx = px(x, b[0]); val sy = S - px(y, b[1]); val sx = S + px(z, b[2])
            if (fx in 0 until S && sy in 0 until S) img.setRGB(fx, sy, c); if (sx in S until 2 * S && sy in 0 until S) img.setRGB(sx, sy, c) }
        g.color = java.awt.Color.WHITE
        for (j in 0 until m.skeleton!!.size) { val x = r.jointsWorld[j * 3]; val y = r.jointsWorld[j * 3 + 1]; val z = r.jointsWorld[j * 3 + 2]
            g.fillOval(px(x, b[0]) - 4, S - px(y, b[1]) - 4, 8, 8); g.fillOval(S + px(z, b[2]) - 4, S - px(y, b[1]) - 4, 8, 8)
            g.drawString(m.skeleton!!.names[j], px(x, b[0]) + 5, S - px(y, b[1])) }
        out.parentFile.mkdirs(); ImageIO.write(img, "png", out)
    }

    @Test fun bob() { val r = run("bob", "bob.glb"); assertTrue(r.jawVertices > 0) }
    @Test fun boobDragon() {
        val r = run("boob_dragon", "boob_dragon.glb")
        val names = r.boneNames
        println("boob dragon extra bones: " + names.filter { it.startsWith("thigh") || it.startsWith("wing") || it.startsWith("tail") })
        // rebuilt detail levels give a slightly different surface; there the mouth line can land on the throat, which
        // rig v4 keeps off the chest. Then lip-sync must fall back to the head nod (no bone named jaw).
        assertTrue(r.jawVertices > 0 || names.none { it.contains("jaw") })
    }

    /** The skinned pose must stay finite and roughly where the bind pose is, for every state. */
    @Test fun posesStayInPlace() {
        for (f in listOf("bob.glb", "boob_dragon.glb")) {
            val m = AutoRig3D.rig(ProgressiveMesh.build(Glb.read(asset(f).readBytes()))).mesh
            val sk = m.skeleton!!; val pose = Pose3D(sk, m.animations); val bones = FloatArray(sk.size * 16)
            val b = m.bounds(); val h = b[4] - b[1]
            for (state in 0..3) for (t in listOf(0f, 0.7f, 2.3f)) {
                pose.evaluate(t, state, 0.5f, bones)
                assertTrue("$f non-finite bones", bones.all { it.isFinite() })
                var maxMove = 0f
                for (i in 0 until m.vertexCount step 7) { val x = m.pos[i*3]; val y = m.pos[i*3+1]; val z = m.pos[i*3+2]
                    var ox = 0f; var oy = 0f; var oz = 0f
                    for (k in 0..3) { val w = m.weights!![i*4+k]; if (w == 0f) continue; val j = m.joints!![i*4+k] * 16
                        ox += w * (bones[j]*x + bones[j+4]*y + bones[j+8]*z + bones[j+12]); oy += w * (bones[j+1]*x + bones[j+5]*y + bones[j+9]*z + bones[j+13]); oz += w * (bones[j+2]*x + bones[j+6]*y + bones[j+10]*z + bones[j+14]) }
                    maxMove = maxOf(maxMove, kotlin.math.sqrt((ox-x)*(ox-x)+(oy-y)*(oy-y)+(oz-z)*(oz-z))) }
                println("$f state=$state t=$t maxMove=${"%.3f".format(maxMove / h)}h")
                assertTrue("$f state $state moved ${maxMove / h}h", maxMove < 0.35f * h)
            }
        }
    }
}
