package app.belle.assistant.threed

import app.belle.assistant.models.Fetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Against the real GitHub release (network): run with -Dp3d.live=1. Uses the app's own host policy (default Fetcher). */
class LiveReleaseInstallTest {
    private val manifest by lazy { ModelManifest.parse(File("src/main/assets/pixal3d_models.json").readText()) }

    @Test fun installsSmallFilesFromTheLiveRelease() {
        assumeTrue(System.getProperty("p3d.live") == "1")
        val dir = Files.createTempDirectory("p3live").toFile()
        val names = (System.getProperty("p3d.live.files") ?: "naf_q_r1.onnx,naf_q_r1.onnx.data,trellis2_shape_dec_seg4.onnx,trellis2_shape_dec_seg3.onnx").split(',')
        val m = ModelManifest(manifest.version, manifest.release, manifest.files.filter { it.name in names }, manifest.licenses)
        assertEquals(names.size, m.files.size)
        val inst = ModelInstaller(dir, m, Fetcher(), autoRetries = 2, backoffMs = { 1000 })
        val t0 = System.currentTimeMillis()
        inst.installAll(); Thread.sleep(100); while (inst.busy) Thread.sleep(100)
        for (f in m.files) println("live ${f.name}: ${inst.status[f.name]}")
        assertTrue(inst.allDone)
        m.files.forEach { assertEquals(it.sha256, Fetcher.sha256(File(dir, it.name))) }
        println("live install ${m.totalBytes} B in ${System.currentTimeMillis() - t0} ms")
        dir.deleteRecursively()
    }

    @Test fun resumesALiveDownloadWithRange() {
        assumeTrue(System.getProperty("p3d.live") == "1")
        val f = manifest.files.first { it.name == (System.getProperty("p3d.live.resume") ?: "trellis2_shape_dec_seg2.onnx") }
        val dir = Files.createTempDirectory("p3live").toFile(); val dest = File(dir, f.name); val part = File(dest.path + ".part")
        val cut = f.size / 4
        try { Fetcher().fetch(f.url, dest, f.size, f.sha256, cancelled = { part.length() >= cut }) } catch (_: Fetcher.Cancelled) { }
        val kept = part.length(); println("live resume: stopped at $kept of ${f.size}")
        assertTrue(kept >= cut && kept < f.size)
        var first = -1L
        Fetcher().fetch(f.url, dest, f.size, f.sha256, progress = { if (first < 0) first = it })
        println("live resume: first progress after restart $first (> $kept means Range was honoured)")
        assertTrue("server restarted from 0", first > kept)
        assertEquals(f.sha256, Fetcher.sha256(dest))
        dir.deleteRecursively()
    }
}
