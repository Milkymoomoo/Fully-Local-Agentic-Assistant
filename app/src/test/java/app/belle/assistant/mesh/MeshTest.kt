package app.belle.assistant.mesh

import app.belle.assistant.core.Detail3D
import app.belle.assistant.core.Shader3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MeshTest {
    /** Bust-like test body: a wide ellipsoid "torso" with a sphere "head" on a thin neck; UV seam included. */
    private fun bust(seg: Int = 48): Mesh3D {
        val P = FloatList(); val U = FloatList(); val I = IntList()
        fun ell(cy: Float, rx: Float, ry: Float, rz: Float) {
            val base = P.size / 3
            for (i in 0..seg) for (j in 0..seg) { val th = PI * i / seg; val ph = 2 * PI * j / seg
                P.add((rx * sin(th) * cos(ph)).toFloat()); P.add((cy + ry * cos(th)).toFloat()); P.add((rz * sin(th) * sin(ph)).toFloat())
                U.add(j.toFloat() / seg); U.add(i.toFloat() / seg) }
            for (i in 0 until seg) for (j in 0 until seg) { val a = base + i * (seg + 1) + j; val b = a + seg + 1
                I.add(a); I.add(b); I.add(a + 1); I.add(a + 1); I.add(b); I.add(b + 1) }
        }
        ell(0.6f, 0.9f, 0.6f, 0.5f); ell(1.25f, 0.12f, 0.2f, 0.12f); ell(1.75f, 0.38f, 0.38f, 0.38f)
        return Mesh3D(P.toArray(), null, U.toArray(), null, null, null, I.toArray(), null, null)
    }

    @Test fun progressiveMeshIsAPrefix() {
        val m = ProgressiveMesh.build(bust(), minVertices = 50)
        val out = IntArray(m.idx.size)
        val full = ProgressiveMesh.trim(m, m.vertexCount, out)
        // full level = the original non-degenerate triangles
        assertTrue(full >= m.triCount * 0.97)
        var last = Int.MAX_VALUE
        for (frac in listOf(1.0, 0.5, 0.25, 0.1, 0.03)) {
            val n = ProgressiveMesh.verticesForTriangles(m, (m.triCount * frac).toInt())
            val t = ProgressiveMesh.trim(m, n, out)
            assertTrue("triangles fall as the slider goes down", t <= last); last = t
            for (k in 0 until t * 3) assertTrue("only vertices below n are referenced", out[k] < n)
            println("level $frac: vertices=$n triangles=$t")
        }
        assertTrue(last < m.triCount / 10)
        for (i in m.pmParent!!.indices) if (m.pmParent[i] >= 0) assertTrue(m.pmParent[i] < i)
    }

    @Test fun glbRoundTripKeepsProgressiveTablesAndRig() {
        val r = AutoRig3D.rig(ProgressiveMesh.build(bust(24)))
        val back = Glb.read(Glb.write(r.mesh))
        assertEquals(r.mesh.vertexCount, back.vertexCount); assertEquals(r.mesh.triCount, back.triCount)
        assertTrue(back.hasPm); assertEquals(r.mesh.pmParent!!.toList(), back.pmParent!!.toList()); assertEquals(r.mesh.pmTriDeath!!.toList(), back.pmTriDeath!!.toList())
        assertEquals(r.mesh.skeleton!!.size, back.skeleton!!.size); assertEquals(r.boneNames, back.skeleton!!.names.toList()); assertEquals(r.mesh.joints!!.toList(), back.joints!!.toList())
    }

    @Test fun autoRigFindsNeckHeadAndJaw() {
        val r = AutoRig3D.rig(bust())
        val neckY = r.jointsWorld[AutoRig3D.NECK * 3 + 1]
        assertTrue("neck between torso top (1.2) and head bottom (1.37): $neckY", neckY in 1.05f..1.45f)
        assertTrue("jaw has vertices", r.jawVertices > 10)
        // rest pose → joint matrices are identity (global × inverse bind)
        val pose = Pose3D(r.mesh.skeleton!!, emptyList()); val mats = FloatArray(r.mesh.skeleton!!.size * 16)
        pose.evaluate(0f, 0, 0f, mats)
        for (j in 0 until r.mesh.skeleton!!.size) for (k in 0..15) assertEquals(if (k % 5 == 0) 1f else 0f, mats[j * 16 + k], 0.03f)
        // talking opens the jaw: jaw matrix differs from identity
        pose.evaluate(0f, 3, 1f, mats); assertTrue(abs(mats[AutoRig3D.JAW * 16 + 5] - 1f) > 0.01f)
    }

    @Test fun rigidStyleUsesOneBonePerVertex() {
        val r = AutoRig3D.rig(bust(16), "rigid")
        for (v in 0 until r.mesh.vertexCount) { assertEquals(1f, r.mesh.weights!![v * 4], 0f); assertEquals(0f, r.mesh.weights[v * 4 + 1], 0f) }
    }

    @Test fun progressiveCollapseKeepsRigWeights() {
        val rigged = AutoRig3D.rig(bust(24)).mesh
        val pm = ProgressiveMesh.build(rigged)
        // same multiset of (position, joints) pairs: the reorder carries bone IDs with their vertices
        fun key(m: Mesh3D) = (0 until m.vertexCount).map { listOf(m.pos[it * 3], m.pos[it * 3 + 1], m.pos[it * 3 + 2], m.joints!![it * 4], m.joints[it * 4 + 1]) }.sortedBy { it.toString() }
        assertEquals(key(rigged), key(pm))
    }

    @Test fun shaderChoiceIsExclusive() {
        val a = mutableMapOf<String, Any?>("retrostation" to true, "retro64" to true); Shader3D.sanitize(a)
        assertEquals("retrostation", a["shader3d"]); assertTrue("retro64" !in a && "retrostation" !in a)
        val b = mutableMapOf<String, Any?>("shader3d" to "retro64", "retrostation" to true); Shader3D.sanitize(b); assertEquals("retro64", b["shader3d"])
        val c = mutableMapOf<String, Any?>("shader3d" to "bogus"); Shader3D.sanitize(c); assertEquals("none", c["shader3d"])
    }

    @Test fun detailSliderCutsWork() {
        val hi = Detail3D(1f, "home"); val lo = Detail3D(0f, "home")
        assertEquals(700_000, hi.triangles(700_000)); assertTrue(lo.triangles(700_000) <= 1500)
        assertTrue(lo.renderScale < hi.renderScale && lo.mipBias > hi.mipBias && lo.fps(true) < hi.fps(true))
        assertTrue(Detail3D(1f, "widget").fps(true) <= 12 && Detail3D(1f, "widget").fps(false) <= 2); assertTrue(Detail3D(1f, "widget").renderScale <= 0.75f)
    }

    @Test fun objAndGltfImport() {
        val obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 1 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nvt 1 1\nf 1/1 2/2 4/4 3/3\n"
        val m = Import3D.read(mapOf("a.obj" to obj.toByteArray()))
        assertEquals(2, m.triCount); assertTrue(m.nrm != null)
        val glb = Glb.write(m); val g = Glb.read(glb); assertEquals(2, g.triCount)
        // .gltf with an embedded data: URI buffer
        val bin = java.io.ByteArrayOutputStream()
        val bb = java.nio.ByteBuffer.allocate(3 * 12 + 3 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { bb.putFloat(it) }; shortArrayOf(0, 1, 2).forEach { bb.putShort(it) }; bin.write(bb.array())
        val uri = "data:application/octet-stream;base64," + java.util.Base64.getEncoder().encodeToString(bin.toByteArray())
        val gltf = """{"asset":{"version":"2.0"},"buffers":[{"uri":"$uri","byteLength":42}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"nodes":[{"mesh":0,"translation":[0,0,5]}],"scenes":[{"nodes":[0]}]}"""
        val t = Import3D.read(mapOf("x.gltf" to gltf.toByteArray()))
        assertEquals(1, t.triCount); assertEquals(5f, t.pos[2], 1e-6f)
    }
}
