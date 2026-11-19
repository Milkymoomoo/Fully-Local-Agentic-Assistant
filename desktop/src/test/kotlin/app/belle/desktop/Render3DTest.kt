package app.belle.desktop

import android.graphics.Bitmap
import app.belle.assistant.Prefs
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.avatar.Avatar3DRenderer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Android 3D renderer, unchanged, through the desktop GL shims: imports the bundled default avatars (rig v6) and
 * renders frames for every surface and shader. Needs a display (Windows desktop, or Xvfb on Linux). Writes PNGs to build/render3d.
 */
class Render3DTest {
    @Test fun rendersBundledAvatars() {
        assumeTrue(System.getenv("DISPLAY") != null || Paths.isWindows)
        assumeTrue("no GPU / OpenGL 3 on this machine (CI runner)", System.getenv("BELLE_CI_NO_GPU") == null)
        TestHome.use()
        val out = File("build/render3d").apply { mkdirs() }
        for ((id, asset) in listOf("default" to "bob.glb", "t2" to "boob_dragon.glb")) {
            val bytes = File("../app/src/main/assets/default_avatars/$asset").readBytes()
            Avatar3DFiles.import(DesktopContext, id, mapOf(asset to bytes))
            assertTrue(Avatar3DFiles.has(DesktopContext, id))
            val p = Prefs(DesktopContext, id)
            for (shader in app.belle.assistant.core.Shader3D.entries) {
                p.shader3d = shader
                for (t in listOf(0f, 0.7f)) {
                    val f = Avatar3DRenderer.renderBlocking(Avatar3DFiles.request(DesktopContext, p, "home", 384, 384, t, 2, 0.6f), 60_000)
                    assertTrue("render failed: ${Avatar3DRenderer.lastError}", f != null)
                    val px = IntArray(f!!.bitmap.width * f.bitmap.height); f.bitmap.getPixels(px, 0, f.bitmap.width, 0, 0, f.bitmap.width, f.bitmap.height)
                    val covered = px.count { (it ushr 24) > 0 } / px.size.toFloat()
                    println("$asset ${shader.name} t=$t: ${f.stats} covered=${"%.2f".format(covered)}")
                    assertTrue("avatar not visible ($covered)", covered in 0.03f..0.97f)
                    File(out, "${id}_${shader.name}_$t.png").outputStream().use { f.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
