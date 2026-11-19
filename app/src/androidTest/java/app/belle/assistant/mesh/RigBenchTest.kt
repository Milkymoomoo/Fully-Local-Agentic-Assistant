package app.belle.assistant.mesh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device timing of the first-launch import of the built-in avatars (read → detail levels → auto-rig). A benchmark,
 * not a check: runs only with `-Pandroid.testInstrumentationRunnerArguments.rigbench=1` (minutes on a software emulator).
 */
@RunWith(AndroidJUnit4::class)
class RigBenchTest {
    @Test fun builtInAvatarsImportTime() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("rigbench") != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (f in listOf("bob.glb", "boob_dragon.glb")) {
            val bytes = ctx.assets.open("default_avatars/$f").use { it.readBytes() }
            var t = System.nanoTime(); val m = Glb.read(bytes).withNormals(); val tr = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); val pm = ProgressiveMesh.build(m); val tp = (System.nanoTime() - t) / 1e6
            t = System.nanoTime(); AutoRig3D.rig(pm); val tg = (System.nanoTime() - t) / 1e6
            Log.i("RigBench", "$f read=${tr.toInt()} ms pm=${tp.toInt()} ms rig=${tg.toInt()} ms ${AutoRig3D.lastRelax}")
        }
    }
}
