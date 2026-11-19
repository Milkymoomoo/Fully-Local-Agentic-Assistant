package app.belle.assistant.threed

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixture from tools/pixal3d_port/flow/shape_dec_export.py's own neighbours / subdivide / to_mesh (whose full-size
 *  run reproduces the ComfyUI reference mesh: 621,145 vertices within 6e-8). */
class ShapeDecoderTest {
    private val j = JSONObject(javaClass.classLoader!!.getResourceAsStream("pixal3d/shape_dec_tiny.json")!!.readBytes().decodeToString())
    private fun ints(k: String) = j.getJSONArray(k).let { a -> IntArray(a.length()) { a.getInt(it) } }
    private fun floats(k: String) = j.getJSONArray(k).let { a: JSONArray -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }

    @Test fun neighbourMapMatches() {
        assertArrayEquals(ints("nbr").map { it.toLong() }.toLongArray(), ShapeDecoder.neighbours(ints("coords")))
    }

    @Test fun subdivisionMatches() {
        val s = ShapeDecoder.subdivide(ints("coords"), floats("sub"), floats("hc"), floats("h"))
        assertArrayEquals(ints("nc"), s.coords); assertArrayEquals(floats("hg"), s.hg, 0f); assertArrayEquals(floats("skip"), s.skip, 0f)
    }

    @Test fun dualGridMeshMatches() {
        val (v, t) = ShapeDecoder.toMesh(ints("nc"), floats("out"), 12)
        assertArrayEquals(floats("verts"), v, 2e-6f); assertArrayEquals(ints("tris"), t)
        assertEquals(66, t.size / 3)
    }
}
