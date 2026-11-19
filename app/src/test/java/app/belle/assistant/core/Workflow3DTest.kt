package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Workflow3DTest {
    private val json = File("../art/trellis2_workflow.json").takeIf { it.exists() } ?: File("art/trellis2_workflow.json")

    @Test fun parsesTheOfficialWorkflowIncludingModelPatches() {
        val s = Workflow3DSettings.parse(json.readText())
        assertEquals("pixal3d", s.engine)
        assertFalse(s.removeBackground)
        assertEquals(listOf("Structure", "Shape", "Shape detail", "Texture"), s.stages.map { it.name })
        assertEquals(listOf(12, 20, 12, 12), s.stages.map { it.steps })
        assertEquals(listOf(7.5, 7.5, 7.5, 1.0), s.stages.map { it.cfg })
        assertEquals(listOf(56L, 42L), s.stages.take(2).map { it.seed })
        assertTrue(s.stages[2].randomSeed); assertEquals("simple", s.stages[2].scheduler)
        // patches found by walking each sampler's model input
        val st = s.stage("Structure")!!; val sh = s.stage("Shape")!!
        assertEquals(5.0, st.shift!!, 0.0); assertEquals(0.7, st.rescale!!, 0.0); assertEquals(0.667, st.overrideStart!!, 1e-9)
        assertEquals(null, sh.shift); assertEquals(0.5, sh.rescale!!, 0.0); assertEquals(0.769, sh.overrideStart!!, 1e-9)
        assertEquals(700_000, s.decimateTarget); assertEquals(4096, s.textureSize)
        assertEquals(1024, s.cropSize); assertEquals(1.1, s.cropPadding, 1e-9)
        // the built-in "official" values are exactly what parsing the official file gives (minus notes)
        assertEquals(Workflow3DSettings.OFFICIAL.stages.take(2), s.stages.take(2))
    }

    @Test fun rejectsFilesThatAreNotAPixal3DWorkflow() {
        assertTrue(runCatching { Workflow3DSettings.parse("""{"nodes":[],"links":[]}""") }.isFailure)
        assertTrue(runCatching { Workflow3DSettings.parse("""{"prompt":{}}""") }.isFailure)
        assertTrue(runCatching { Workflow3DSettings.parse("not json") }.isFailure)
    }

    @Test fun overridesApplyAndRoundTrip() {
        val base = Workflow3DSettings.OFFICIAL
        val o = Workflow3DOverrides.read(null)
        Workflow3DOverrides.setStage(o, "Structure", "steps", 9.0); Workflow3DOverrides.setStage(o, "Structure", "seed", 1234.0)
        Workflow3DOverrides.setStage(o, "Shape", "scheduler", "simple")
        o["cropSize"] = 768.0; o["decimateTarget"] = 100000.0; o["removeBackground"] = true
        val s = base.withOverrides(Workflow3DOverrides.write(o))
        assertEquals(9, s.stage("Structure")!!.steps); assertEquals(1234L, s.stage("Structure")!!.seed)
        assertEquals("simple", s.stage("Shape")!!.scheduler)
        assertEquals(768, s.cropSize); assertEquals(100000, s.decimateTarget); assertTrue(s.removeBackground)
        assertTrue(Workflow3DOverrides.has(o, "Structure", "steps")); assertFalse(Workflow3DOverrides.has(o, "Shape", "steps"))
        // removing every edit gives the workflow back
        Workflow3DOverrides.setStage(o, "Structure", "steps", null); Workflow3DOverrides.setStage(o, "Structure", "seed", null)
        Workflow3DOverrides.setStage(o, "Shape", "scheduler", null)
        o.remove("cropSize"); o.remove("decimateTarget"); o.remove("removeBackground")
        assertEquals(base, base.withOverrides(Workflow3DOverrides.write(o)))
        assertEquals(base, base.withOverrides("{\"textureSize\":333}"))   // invalid values ignored
        assertEquals(base, base.withOverrides("{broken"))
    }
}
