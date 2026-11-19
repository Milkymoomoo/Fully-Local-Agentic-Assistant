package app.belle.assistant.threed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Fixtures in resources/pixal3d were made with ComfyUI's own functions (tools/pixal3d_port/make_fixtures.py). */
class P3MathTest {
    private fun f32(name: String): FloatArray {
        val b = javaClass.classLoader!!.getResourceAsStream("pixal3d/$name.f32")!!.readBytes()
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); return FloatArray(fb.remaining()).also { fb.get(it) }
    }
    private fun maxErr(a: FloatArray, b: FloatArray): Float { assertEquals(a.size, b.size); var m = 0f; for (i in a.indices) m = maxOf(m, abs(a[i] - b[i])); return m }
    private fun planes(a: FloatArray, n: Int) = Array(3) { c -> a.copyOfRange(c * n, (c + 1) * n) }

    @Test fun lanczosMatchesPilWithinOneLevel() {
        val src = P3Math.Img(50, 40, planes(f32("crop_img"), 2000))
        val d = P3Math.lanczos(src, 23, 17); assertTrue(maxErr(d.px.flatMap { it.asList() }.toFloatArray(), f32("lz_out_23x17")) <= 1.01f / 255)
        val u = P3Math.lanczos(src, 71, 64); assertTrue(maxErr(u.px.flatMap { it.asList() }.toFloatArray(), f32("lz_out_71x64")) <= 1.01f / 255)
    }

    @Test fun cropToMaskMatchesComfy() {
        val src = P3Math.Img(50, 40, planes(f32("crop_img"), 2000))
        val o = P3Math.cropToMask(src, f32("crop_mask"), 32)
        val e = maxErr(o.px.flatMap { it.asList() }.toFloatArray(), f32("crop_out"))
        assertTrue("crop max err $e", e <= 2.01f / 255)
    }

    @Test fun ropeTables() {
        val (c, s) = P3Math.dinoRope(64); assertTrue(maxErr(c, f32("dino_cos_64")) < 1e-5f); assertTrue(maxErr(s, f32("dino_sin_64")) < 1e-5f)
        val (nc, ns) = P3Math.nafRope(8); assertTrue(maxErr(nc, f32("naf_cos_8")) < 1e-5f); assertTrue(maxErr(ns, f32("naf_sin_8")) < 1e-5f)
    }

    @Test fun projectionLrAndOnDemandNafMatchComfy() {
        val co = f32("proj_coords").map { it.toInt() }.toIntArray(); val n = co.size / 3
        val uv = P3Math.uvNdc(co, 16, 46.82, 64)
        val v = f32("proj_v"); val q = f32("proj_q"); val C = 8
        val lr = FloatArray(n * C); P3Math.sampleMap(v, C, 4, 4, uv, lr, C, 0)
        assertTrue("lr", maxErr(lr, f32("proj_lr")) < 1e-4f)
        // full-map sampling of ComfyUI's own NAF output, then the on-demand path
        val hrFull = FloatArray(n * C); P3Math.sampleMap(f32("proj_hrmap"), C, 16, 16, uv, hrFull, C, 0)
        assertTrue("hr full", maxErr(hrFull, f32("proj_hr")) < 1e-4f)
        val k = P3Math.avgPool(q, 256, 16, 16, 4)
        val hr = FloatArray(n * C); P3Math.sampleNaf(q, 16, 16, k, v, C, 4, 4, uv, hr, C, 0)
        val e = maxErr(hr, f32("proj_hr")); assertTrue("naf on demand err $e", e < 1e-4f)
    }

    @Test fun antialiasedBilinearMatchesTorch() {
        val src = P3Math.Img(50, 40, planes(f32("crop_img"), 2000))
        val o = P3Math.resizeAA(src, 23, 17); val e = maxErr(o.px.flatMap { it.asList() }.toFloatArray(), f32("aa_out_23x17"))
        assertTrue("aa err $e", e < 1e-5f)
    }

    @Test fun mogeFovMatchesComfyRecovery() {
        val meta = String(javaClass.classLoader!!.getResourceAsStream("pixal3d/meta.json")!!.readBytes())
        val want = Regex("\"moge_fov\": ([0-9.]+)").find(meta)!!.groupValues[1].toDouble()
        val fov = P3Math.mogeFov(f32("moge_p"), f32("moge_m"), 120, 120)
        assertTrue("fov $fov vs $want", abs(fov - want) < 0.05)
    }
}
