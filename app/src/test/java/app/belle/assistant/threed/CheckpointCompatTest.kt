package app.belle.assistant.threed

import app.belle.assistant.core.AtomicFiles
import app.belle.assistant.core.BuildJobRules
import app.belle.assistant.core.BuildLog
import app.belle.assistant.core.BuildRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A job left by 0.1.3 must resume in this version. Real case (OUKITEL WP100 TITAN): "for Milly · saved progress:
 * detailed shape step 20/20 · tries used 3 of 3" after an out-of-memory failure building the surface.
 * Fixture: test resources pixal3d/job_0.1.3/ = files/pixal3d_job/ as 0.1.3 writes it (sealed job.json without app_version,
 * attempts 3, the official 12 + 20 plan; sealed photo; checkpoints cond / ss 12 / vox / shape 20). It was written with
 * CheckpointStore / AtomicFiles / Pixal3DCheckpoint, whose source is unchanged since 0.1.3 (git diff 3ec326e is empty);
 * being a committed file, any later format change that breaks it fails here.
 * Regenerate (only if you mean to): -Dp3d.makefixture=1.
 */
class CheckpointCompatTest {
    private val n = 6   // voxels in the fixture (tiny; the format doesn't depend on the count)
    private fun fixture(): File {
        val src = File(javaClass.classLoader!!.getResource("pixal3d/job_0.1.3/job.json")!!.toURI()).parentFile
        val d = Files.createTempDirectory("job013").toFile(); src.copyRecursively(d); return d
    }

    @Test fun a013JobAtDetailedShape20Of20ResumesAfterTheUpdate() {
        val d = fixture()
        val job = JSONObject(String(AtomicFiles.readSealed(File(d, "job.json"))!!))
        assertEquals(3, job.getInt("attempts")); assertTrue(!job.has("app_version"))
        // as 0.1.3 left it: no more tries
        assertNotNull(BuildJobRules.blocked(job, BuildJobRules.UNVERSIONED, 3))
        // this version: tries reset, nothing blocks it
        val msg = BuildJobRules.afterUpdate(job, 6, "0.1.5")
        assertNotNull(msg); assertEquals(0, job.getInt("attempts")); assertEquals(6, job.getInt("app_version"))
        assertNull(BuildJobRules.blocked(job, 6, 3))
        assertNull("only once", BuildJobRules.afterUpdate(job, 6, "0.1.5"))

        val plan = Run3DPlan.fromJson(job.getString("plan"))
        assertEquals(12, plan.structure.steps); assertEquals(20, plan.shape.steps)
        val ck = Pixal3DCheckpoint(File(d, "ck"))
        assertEquals("detailed shape step 20/20", ck.describe(plan.structure.steps, plan.shape.steps))
        assertEquals(n * 3, ck.vox()!!.size); assertEquals(20, ck.shape()!!.first); assertEquals(n * 32, ck.shape()!!.second.size)
        assertNotNull(ck.cond()); assertNotNull(AtomicFiles.readSealed(File(d, "photo.ck")))

        // the resumed run goes straight to the surface: no NAF map, no model session (the model folder is empty here)
        val (snap, size) = ck.cond()!!
        val c = Pixal3D.Cond(snap.fov, snap.crop, Pixal3D.Dino(size, snap.global, snap.patches), null)
        val p3 = Pixal3D(Files.createTempDirectory("nomodels").toFile())
        val (_, sh, _) = Pixal3D.samplerInputs(plan)
        val lat = p3.shape512(c, Pixal3D.Voxels(ck.vox()!!, 32), p = sh, ck = ck)
        assertEquals(n * 32, lat.size); assertTrue("no stage ran", p3.stats.isEmpty())
    }

    @Test fun outOfMemoryIsRecordedAsSuchAndNotRetriedOnTheSameVersion() {
        val e = OutOfMemoryError("Failed to allocate a 151620624 byte allocation with 3181456 free bytes and 3106KB until OOM, target footprint 536870912, growth limit 536870912")
        val f = BuildJobRules.classify(RuntimeException("wrapped", e)); assertEquals(BuildJobRules.OOM, f.kind)
        assertEquals(BuildJobRules.ERROR, BuildJobRules.classify(IllegalStateException("ONNX Runtime run failed: x")).kind)
        val job = JSONObject().put("attempts", 1).put("app_version", 6)
        BuildJobRules.recordFailure(job, f, "Building the surface", 6)
        assertTrue(BuildJobRules.blocked(job, 6, 3)!!.contains("ran out of memory at Building the surface"))
        // a newer version may resume it
        assertNotNull(BuildJobRules.afterUpdate(job, 7, "0.1.6")); assertNull(BuildJobRules.blocked(job, 7, 3))

        // the history says "ran out of memory", also for the record 0.1.3 left as "interrupted"
        val h = BuildLog(File(Files.createTempDirectory("h").toFile(), "h.ck"))
        h.start(1, "p", "Milly", 0); h.finish(1, BuildRecord.OUT_OF_MEMORY, 10, f.message)
        assertTrue(h.last()!!.line().contains("ran out of memory at starting"))
        val legacy = BuildRecord(2, "p", "Milly", 0, 10, "Detailed shape (3843 voxels)", BuildRecord.INTERRUPTED, e.message, resumes = 2)
        assertTrue(legacy.line(), legacy.line().contains("ran out of memory (Failed to allocate") && legacy.line().contains("ended without a result at Detailed shape"))
        assertTrue(BuildRecord(3, "p", "M", 0, 10, "x", BuildRecord.INTERRUPTED, reason = "it crashed (native code)").line().contains("(it crashed (native code))"))
    }


    /** The heap gate rewrites a just-killed post-shape job to look like 0.1.3 left it, then this version resumes it. */
    @Test fun aCurrentJobRewrittenAs013ResumesAfterTheUpdate() {
        val job = JSONObject().put("attempts", 1).put("app_version", 6)
            .put("failure", JSONObject().put("kind", BuildJobRules.ERROR).put("stage", "Building the surface"))
        // same rewrite as scripts/heap_gate.sh (no app_version, tries used 3 of 3, no failure record — as 0.1.3 left it)
        job.remove("app_version"); job.remove("updated_from"); job.remove("failure"); job.remove("open_try")
        job.put("attempts", 3)
        assertTrue(!job.has("app_version")); assertEquals(3, job.getInt("attempts"))
        assertNotNull(BuildJobRules.blocked(job, 6, 3))   // 3 of 3, cannot resume on its own
        val msg = BuildJobRules.afterUpdate(job, 6, "0.1.5")
        assertNotNull(msg); assertTrue(msg!!.contains("tries reset from 3 to 0"))
        assertEquals(0, job.getInt("attempts")); assertEquals(6, job.getInt("app_version"))
        assertNull(BuildJobRules.blocked(job, 6, 3))
    }

    @Test fun makeFixture() {
        if (System.getProperty("p3d.makefixture") != "1") return
        val d = File("src/test/resources/pixal3d/job_0.1.3").apply { deleteRecursively(); mkdirs() }
        // the plan as 0.1.3's Run3DPlan.toJson wrote it for the official workflow kept at 12 + 20 steps
        val p0 = Run3DPlan.plan(app.belle.assistant.core.Workflow3DSettings.OFFICIAL, emptyMap(), Run3DPlan.Device.estimate(11.2))
        val plan = Run3DPlan.toJson(Run3DPlan.withFixedSeeds(p0.copy(structure = p0.structure.copy(steps = 12), shape = p0.shape.copy(steps = 20))) { 1234567L })
        val job = JSONObject().put("profile", "b156d1aa").put("name", "Milly").put("steps", 0).put("import", true).put("plan", plan)
            .put("attempts", 3).put("created", 1795087971992L).put("build_id", 1795087971992L)
        AtomicFiles.writeSealed(File(d, "job.json"), job.toString().toByteArray())
        AtomicFiles.writeSealed(File(d, "photo.ck"), byteArrayOf(-119, 80, 78, 71))
        val ck = Pixal3DCheckpoint(File(d, "ck"))
        val img = P3Math.Img(4, 4, Array(3) { c -> FloatArray(16) { it * 0.05f + c * 0.1f } })
        ck.saveCond(48.5, img, FloatArray(16) { 1f }, Pixal3D.Dino(32, FloatArray(5 * 1024) { 0.01f * (it % 7) }, FloatArray(1024 * 4) { 0.02f * (it % 5) }))
        for (k in 11..12) ck.saveSs(k, FloatArray(32 * 4096) { (it % 11) * 0.1f })
        ck.saveVox(IntArray(n * 3) { 14 + it % 4 })
        for (k in 19..20) ck.saveShape(k, FloatArray(n * 32) { (it % 13) * 0.05f })
    }
}
