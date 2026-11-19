package app.belle.assistant.threed

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowSamplerTest {
    @Test fun shapeScheduleMatchesComfyTimesteps() {
        // the reference img2shape_512 calls saw t = 1000, 981.8746 (20 steps, shift 3)
        val s = FlowSampler.sigmas(20, 3.0)
        assertEquals(1.0, s[0], 1e-9); assertEquals(981.8746, s[1] * 1000, 1e-3); assertEquals(0.0, s[20], 0.0)
    }

    @Test fun structureScheduleMatchesComfy() {
        val want = doubleArrayOf(1.0, 0.9805, 0.9577, 0.9307, 0.8982, 0.8583, 0.8082, 0.7434, 0.6563, 0.5331, 0.3453, 0.0244, 0.0)
        val s = FlowSampler.sigmas(12, 5.0); for (i in want.indices) assertEquals(want[i], s[i], 1e-4)
        // CFGOverride runs on the shift-3 model sampling (it precedes ModelSamplingSD3 in the workflow)
        assertEquals(0.59964, FlowSampler.sigmaHi(FlowSampler.Params.STRUCTURE), 1e-5)
    }

    @Test fun samplerMatchesNumpyReference() {
        // flow/shape_sample.py's loop on a toy velocity v = 0.5x ± k·t/1000 (numbers from that script)
        val x0 = FloatArray(64) { ((it % 7) - 3) / 3f }
        val out = FlowSampler.sample(x0, FlowSampler.Params.SHAPE_512, { x, t, c -> FloatArray(x.size) { 0.5f * x[it] + (if (c) 0.1f else -0.2f) * t / 1000f } })
        val want = floatArrayOf(-1.19334f, -0.9931f, -0.79285f, -0.59261f, -0.39236f, -0.19212f)
        for (i in want.indices) assertEquals(want[i], out[i], 2e-4f)
        assertEquals(0.474008, FlowSampler.sigmaHi(FlowSampler.Params.SHAPE_512), 1e-6)
    }

    @Test fun occupancyPoolsAndOrdersXMajor() {
        val r = 4; val l = FloatArray(r * r * r) { -1f }
        l[(3 * r + 0) * r + 1] = 1f      // (3,0,1) → cell (1,0,0)
        l[(0 * r + 2) * r + 3] = 2f      // (0,2,3) → cell (0,1,1)
        assertEquals(listOf(0, 1, 1, 1, 0, 0), Pixal3D.occupancy(l, r).toList())
    }
}
