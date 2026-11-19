package app.belle.desktop

import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.threed.Pixal3DJob
import app.belle.assistant.threed.Port3D
import app.belle.desktop.accel.Backend
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Photo → 3D on the desktop CPU, the way the program runs it: the phone's Pixal3DJob (job record, sealed checkpoints,
 * build log) on the desktop natives, with the models-3d-v2 set. The run is killed during the detailed-shape stage,
 * then resumed from its checkpoints, and must finish with a model on the assistant.
 * Opt-in: BELLE_NATIVES + -Ddesk.p3d=<folder with the models-3d-v2 files>. Slow (tens of minutes on a CPU).
 */
class Pixal3DDesktopTest {
    @Test fun photoTo3DStopAndResume() {
        val models = System.getProperty("desk.p3d")?.let(::File)
        assumeTrue(models?.isDirectory == true && System.getProperty("belle.natives") != null)
        if (System.getProperty("belle.home") == null) System.setProperty("belle.home", File("build/p3d_home").absolutePath.also { File(it).deleteRecursively() })
        Natives.install(Backend.CPU, Backend.CPU)
        val ctx = DesktopContext
        val dst = Port3D.dir(ctx)
        models!!.listFiles()!!.filter { it.name.endsWith(".onnx") || it.name.endsWith(".onnx.data") }.forEach { f ->
            val d = File(dst, f.name); if (!d.exists()) runCatching { java.nio.file.Files.createSymbolicLink(d.toPath(), f.absoluteFile.toPath()) }.onFailure { f.copyTo(d) }
        }
        assertTrue(Pixal3DJob.available(ctx), "missing: ${Pixal3DJob.missing(ctx)}")
        val id = ProfileStore.activeId(ctx)
        val photoFile = File(System.getProperty("p3d.image") ?: "../art/dragoness.png")
        // a real death: the first run goes in a child process that is killed once the detailed shape starts (as a
        // crash or power cut would); this process then resumes it from the checkpoints
        val t0 = System.currentTimeMillis()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-Xmx" + (Runtime.getRuntime().maxMemory() shr 20) + "m", "-cp", System.getProperty("java.class.path"),
            "-Dbelle.home=" + System.getProperty("belle.home"), "-Dbelle.natives=" + System.getProperty("belle.natives"),
            "-Djava.library.path=" + System.getProperty("java.library.path"), "app.belle.desktop.Pixal3DChild", id, photoFile.absolutePath)
            .inheritIO().start()
        var seen = 0L
        while (child.isAlive) {
            Thread.sleep(2000)
            val st = Pixal3DJob.pendingJob(ctx)?.optJSONObject("open_try")?.optString("stage").orEmpty()
            // the job record names the stage ("Detailed shape (N voxels)"); give it a minute and a half into it so
            // some sampling steps are checkpointed, then kill it mid-step
            if (st.startsWith("Detailed shape") && seen == 0L) seen = System.currentTimeMillis()
            if (seen > 0 && System.currentTimeMillis() - seen > 90_000) { println("killing the build in '$st' after ${(System.currentTimeMillis() - t0) / 1000} s"); child.destroyForcibly().waitFor(); break }
        }
        assertTrue(!child.isAlive)
        assertNotNull(Pixal3DJob.pendingJob(ctx), "no job record left to resume (exit ${child.exitValue()})")
        val t1 = System.currentTimeMillis()
        val second = Pixal3DJob.resume(ctx)
        println("resumed: $second after ${(System.currentTimeMillis() - t1) / 1000} s")
        assertTrue(Pixal3DJob.lastGlb(ctx).length() > 10_000, "no model written")
        assertTrue(Avatar3DFiles.has(ctx, id), "model not on the assistant")
        File("build/p3d_desktop.glb").writeBytes(Pixal3DJob.lastGlb(ctx).readBytes())
        println(Pixal3DJob.lastRun(ctx).takeIf { it.exists() }?.readText())
    }
}

/** The first, killed run of [Pixal3DDesktopTest] (args: profile id, photo file). */
object Pixal3DChild {
    @JvmStatic fun main(args: Array<String>) {
        app.belle.assistant.core.Platform.desktop = true
        Natives.install(Backend.CPU, Backend.CPU)
        println(Pixal3DJob.run(DesktopContext, args[0], android.graphics.BitmapFactory.decodeFile(args[1])!!))
    }
}
