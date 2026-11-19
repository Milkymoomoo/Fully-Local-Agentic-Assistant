package app.belle.assistant.threed

import app.belle.assistant.core.Workflow3DOverrides
import app.belle.assistant.core.Workflow3DSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Run3DPlanTest {
    private val official = Workflow3DSettings.parse((File("../art/trellis2_workflow.json").takeIf { it.exists() } ?: File("art/trellis2_workflow.json")).readText())
    private val fast = Run3DPlan.Device(12.0, 19.9, 6.6, measured = false)
    private fun item(p: Run3DPlan.Plan, stage: String?, key: String) = p.items.first { it.stage == stage && it.key == key }

    /** A workflow with every sampler value changed: structure 9 steps, cfg 6, seed 77, "simple"; shape 14 steps, cfg 4.5, seed 5. */
    private fun edited(): Workflow3DSettings = official.copy(stages = official.stages.map { when (it.name) {
        "Structure" -> it.copy(steps = 9, cfg = 6.0, seed = 77, scheduler = "simple")
        "Shape" -> it.copy(steps = 14, cfg = 4.5, seed = 5); else -> it } })

    @Test fun importedValuesReachTheSampler() {
        val plan = Run3DPlan.plan(edited(), emptyMap(), fast)
        val (ss, sh, seeds) = Pixal3D.samplerInputs(plan)
        assertEquals(FlowSampler.Params(9, 5.0, 6.0, 0.7, 0.667, 3.0, "simple"), ss)
        assertEquals(FlowSampler.Params(14, 3.0, 4.5, 0.5, 0.769, 3.0, "normal"), sh)
        assertEquals(77L to 5L, seeds)

        // and the sampler really runs them: steps, cfg (cond+uncond until the override point), the scheduler's sigmas
        val sigmasSeen = ArrayList<Double>(); var calls = 0; var uncond = 0; var cfgSeen = HashSet<Double>()
        FlowSampler.sample(FlowSampler.noise(64, seeds.first), ss, { x, _, cond -> calls++; if (!cond) uncond++; FloatArray(x.size) },
            { _, sg, cfg -> sigmasSeen += sg; cfgSeen += cfg; true })
        assertEquals(9, sigmasSeen.size)
        assertEquals(FlowSampler.simpleSigmas(9, 5.0).take(9), sigmasSeen)
        assertEquals(setOf(6.0, 1.0), cfgSeen)
        assertEquals(FlowSampler.modelCalls(ss), calls); assertTrue(uncond in 1 until 9)
        // the seed decides the starting noise
        assertTrue(FlowSampler.noise(64, 77).contentEquals(FlowSampler.noise(64, seeds.first)))
        assertFalse(FlowSampler.noise(64, 78).contentEquals(FlowSampler.noise(64, seeds.first)))
    }

    @Test fun randomSeedIsDrawnPerRun() {
        val wf = official.copy(stages = official.stages.map { if (it.name == "Shape") it.copy(randomSeed = true) else it })
        val (_, _, seeds) = Pixal3D.samplerInputs(Run3DPlan.plan(wf, emptyMap(), fast), random = { 4242L })
        assertEquals(56L to 4242L, seeds)
    }

    @Test fun officialWorkflowRunsAsIsOnAFastPhone() {
        val p = Run3DPlan.plan(official, emptyMap(), fast)
        assertEquals(12, p.structure.steps); assertEquals(20, p.shape.steps)
        assertTrue(p.estMinutes < 30)
        assertFalse(item(p, "Structure", "steps").adjusted)
        assertEquals(1024, p.cropSize)
        assertEquals(250_000, p.maxTriangles)                       // the workflow's 700k, capped for a 12 GB phone
        assertTrue(item(p, null, "decimateTarget").adjusted)
    }

    @Test fun slowPhoneGetsFewerStepsWithAReason() {
        val slow = Run3DPlan.Device(8.0, 80.0, 25.0, measured = true)
        val p = Run3DPlan.plan(official, emptyMap(), slow)
        assertTrue(p.structure.steps < 12 && p.shape.steps < 20)
        assertTrue("fits the budget: ${p.estMinutes}", p.estMinutes <= 30.0)
        val it = item(p, "Structure", "steps")
        assertTrue(it.adjusted); assertEquals("12", it.workflow); assertTrue(it.note!!.contains("this phone's last run"))
        // both stages scaled together, in the same proportion
        assertEquals(p.structure.steps / 12.0, p.shape.steps / 20.0, 0.1)
    }

    @Test fun overridesWinAndResetRestoresTheFit() {
        val slow = Run3DPlan.Device(8.0, 80.0, 25.0, measured = true)
        val o = Workflow3DOverrides.read(null)
        Workflow3DOverrides.setStage(o, "Structure", "steps", 30.0); o["decimateTarget"] = 280000.0; o["cropSize"] = 2048.0
        val p = Run3DPlan.plan(official, o, slow)
        assertEquals(30, p.structure.steps)                      // your value, even over the time budget
        assertTrue(item(p, "Structure", "steps").overridden)
        assertEquals(280_000, p.maxTriangles)                    // over the auto cap, inside the hard range
        assertEquals(1024, p.cropSize)                           // hard range 512..1024 still applies
        o.clear()
        val r = Run3DPlan.plan(official, o, slow)
        assertTrue(r.structure.steps < 12); assertFalse(r.items.any { it.overridden })
    }

    @Test fun deviceCapsForMemory() {
        val small = Run3DPlan.plan(official, emptyMap(), Run3DPlan.Device(6.0, 19.9, 6.6, false))
        assertEquals(768, small.cropSize); assertEquals(150_000, small.maxTriangles)
        assertTrue(item(small, null, "cropSize").note!!.contains("under 8 GB"))
        val big = Run3DPlan.plan(official, emptyMap(), Run3DPlan.Device(16.0, 19.9, 6.6, false))
        assertEquals(1024, big.cropSize); assertEquals(250_000, big.maxTriangles)
    }

    @Test fun unsupportedSamplerFallsBackWithANote() {
        val wf = official.copy(stages = official.stages.map { if (it.name == "Shape") it.copy(sampler = "dpmpp_2m", scheduler = "karras") else it })
        val p = Run3DPlan.plan(wf, emptyMap(), fast)
        assertEquals("euler", p.shape.sampler); assertEquals("normal", p.shape.scheduler)
        assertTrue(p.notes.any { "dpmpp_2m" in it }); assertTrue(item(p, "Shape", "scheduler").adjusted)
    }

    @Test fun measuredTimesComeFromLastRun() {
        assertEquals(20.0 to 7.0, Run3DPlan.measured("""{"ss_ms":280000,"ss_calls":14,"shape_ms":147000,"shape_calls":21}"""))
        assertEquals(null, Run3DPlan.measured(null)); assertEquals(null, Run3DPlan.measured("{}"))
    }

    @Test fun phoneDefaultsAreTheTestedEightAndTwelve() {
        val p = Pixal3D.DEFAULT_PLAN
        assertEquals(8, p.structure.steps); assertEquals(12, p.shape.steps)
        assertEquals(FlowSampler.Params.STRUCTURE_PHONE, p.structure.params()); assertEquals(FlowSampler.Params.SHAPE_512_PHONE, p.shape.params())
    }

    @Test fun planJsonRoundTripAndFixedSeeds() {
        val plan = Run3DPlan.plan(edited(), emptyMap(), fast)
        val back = Run3DPlan.fromJson(Run3DPlan.toJson(plan))
        assertEquals(plan.structure, back.structure); assertEquals(plan.shape, back.shape)
        assertEquals(plan.maxTriangles, back.maxTriangles); assertEquals(plan.cropSize, back.cropSize)
        val rnd = plan.copy(structure = plan.structure.copy(randomSeed = true), shape = plan.shape.copy(randomSeed = true))
        var n = 100L; val fixed = Run3DPlan.withFixedSeeds(rnd) { n++ }
        assertEquals(100L, fixed.structure.seed); assertEquals(101L, fixed.shape.seed)
        assertFalse(fixed.structure.randomSeed || fixed.shape.randomSeed)
        // a resumed run reads the same seeds back
        assertEquals(fixed.shape, Run3DPlan.fromJson(Run3DPlan.toJson(fixed)).shape)
    }

    @Test fun gpuKeepsMoreStepsAndCpuTimesDontCount() {
        val cpu = Run3DPlan.plan(official, emptyMap(), Run3DPlan.Device(16.0, 80.0, 25.0, measured = false))
        val gpu = Run3DPlan.plan(official, emptyMap(), Run3DPlan.Device(16.0, 80.0 / 8, 25.0 / 8, measured = false, accel = "cuda", accelLabel = "NVIDIA CUDA"))
        assertTrue("gpu ${gpu.shape.steps} vs cpu ${cpu.shape.steps}", gpu.shape.steps > cpu.shape.steps)
        assertTrue(Run3DPlan.Device.estimate(16.0, "cuda", "NVIDIA CUDA", 8.0).secPerCallShape < Run3DPlan.Device.estimate(16.0).secPerCallShape)
        assertTrue(gpu.estMinutes < cpu.estMinutes)
        val last = """{"ss_ms":278600,"ss_calls":14,"shape_ms":138300,"shape_calls":21}"""
        assertTrue(Run3DPlan.measured(last, "cpu") != null)
        assertTrue(Run3DPlan.measured(last, "cuda") == null)   // a CPU run's times aren't used for the GPU
        assertTrue(Run3DPlan.measured(last.replace("{", "{\"accel\":\"cuda\","), "cuda") != null)
    }
}
