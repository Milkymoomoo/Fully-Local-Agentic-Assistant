package app.belle.assistant.threed

import app.belle.assistant.core.CheckpointStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Kill-and-resume: a run stopped mid-way and continued from its checkpoint must give exactly the uninterrupted result. */
class ResumeTest {
    private fun tmp(): File = Files.createTempDirectory("resume").toFile()
    private val v: (FloatArray, Float, Boolean) -> FloatArray = { x, t, c -> FloatArray(x.size) { 0.5f * x[it] + (if (c) 0.1f else -0.2f) * t / 1000f + 0.01f * (it % 5) } }

    private fun killedAt(p: FlowSampler.Params, noise: FloatArray, killAfter: Int, ck: CheckpointStore) {
        FlowSampler.sample(noise, p, v, onStep = { i, _, _ -> i + 1 < killAfter }, onState = { k, x -> ck.save("ss", k, CheckpointStore.floats(x)) })
    }

    @Test fun killMidRunThenResumeIsExact() {
        for (p in listOf(FlowSampler.Params.STRUCTURE_PHONE, FlowSampler.Params.SHAPE_512_PHONE)) {
            val noise = FlowSampler.noise(256, 42)
            val full = FlowSampler.sample(noise, p, v)
            for (kill in 1 until p.steps) {
                val ck = CheckpointStore(tmp()); killedAt(p, noise, kill, ck)
                val (k, b) = ck.load("ss")!!; assertEquals(kill, k)
                val resumed = FlowSampler.sample(noise, p, v, resume = k to CheckpointStore.floats(b))
                assertArrayEquals("${p.steps} steps, killed after $kill", full, resumed, 0f)
            }
        }
    }

    @Test fun truncatedNewestCheckpointFallsBackOneStepAndIsStillExact() {
        val p = FlowSampler.Params.SHAPE_512_PHONE; val noise = FlowSampler.noise(256, 7)
        val full = FlowSampler.sample(noise, p, v)
        val ck = CheckpointStore(tmp()); killedAt(p, noise, 6, ck)
        // battery died while step 6 was being written: its slot is half a file
        val slot6 = ck.dir.listFiles()!!.first { f -> ck.load("ss")!!.first == 6 && f.readBytes().let { app.belle.assistant.core.AtomicFiles.unseal(it)?.let { u -> java.io.DataInputStream(u.inputStream()).readInt() == 6 } } == true }
        slot6.writeBytes(slot6.readBytes().copyOf(100))
        val (k, b) = ck.load("ss")!!; assertEquals(5, k)
        assertArrayEquals(full, FlowSampler.sample(noise, p, v, resume = k to CheckpointStore.floats(b)), 0f)
    }

    @Test fun pixalCheckpointRoundTripsAndDescribes() {
        val c = Pixal3DCheckpoint(tmp())
        assertEquals("the start", c.describe(8, 12)); assertNull(c.cond())
        val img = P3Math.Img(4, 3, Array(3) { ch -> FloatArray(12) { it * 0.1f + ch } })
        c.saveCond(0.7, img, FloatArray(12) { 1f }, Pixal3D.Dino(32, FloatArray(5) { it.toFloat() }, FloatArray(40) { -it.toFloat() }))
        val (snap, size) = c.cond()!!
        assertEquals(32, size); assertEquals(0.7, snap.fov, 0.0); assertArrayEquals(img.px[2], snap.crop.px[2], 0f); assertEquals(40, snap.patches.size)
        assertEquals("the prepared photo", c.describe(8, 12))
        c.saveSs(3, FloatArray(8) { 1f }); assertEquals("rough shape step 3/8", c.describe(8, 12))
        c.saveVox(intArrayOf(1, 2, 3)); assertArrayEquals(intArrayOf(1, 2, 3), c.vox())
        c.saveShape(9, FloatArray(4)); assertEquals("detailed shape step 9/12", c.describe(8, 12))
        c.saveGlb(byteArrayOf(1, 2)); assertNotNull(c.glb()); assertEquals("the finished model", c.describe(8, 12))
        // the glb file is damaged: the run goes back to the shape checkpoint, it does not import a broken model
        c.store.dir.listFiles()!!.filter { it.name.startsWith("glb.") }.forEach { it.writeBytes(it.readBytes().copyOf(5)) }
        assertNull(c.glb()); assertEquals("detailed shape step 9/12", c.describe(8, 12))
    }
}
