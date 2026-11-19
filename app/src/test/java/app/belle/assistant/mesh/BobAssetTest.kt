package app.belle.assistant.mesh

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** End-to-end on the user's real Pixal3D Bob (art/bob_pixal3d.glb, not bundled). Skipped when the file is absent. */
class BobAssetTest {
    @Test fun importBob() {
        val f = listOf(File("../art/bob_pixal3d.glb"), File("art/bob_pixal3d.glb")).firstOrNull { it.exists() }
        assumeTrue(f != null)
        val rt = Runtime.getRuntime(); System.gc(); val m0 = rt.totalMemory() - rt.freeMemory()
        var t = System.nanoTime()
        val mesh = Glb.read(f!!.readBytes()); val tRead = (System.nanoTime() - t) / 1e9
        t = System.nanoTime(); val pm = ProgressiveMesh.build(mesh); val tPm = (System.nanoTime() - t) / 1e9
        val peak = rt.totalMemory() - rt.freeMemory()
        t = System.nanoTime(); val r = AutoRig3D.rig(pm); val tRig = (System.nanoTime() - t) / 1e9
        t = System.nanoTime(); val out = Glb.write(r.mesh); val tWrite = (System.nanoTime() - t) / 1e9
        File("build/bob_imported.glb").writeBytes(out)
        println("BOB verts=${mesh.vertexCount} tris=${mesh.triCount} read=${"%.1f".format(tRead)}s pm=${"%.1f".format(tPm)}s rig=${"%.1f".format(tRig)}s write=${"%.1f".format(tWrite)}s heapDelta=${(peak - m0) / 1_000_000}MB out=${out.size / 1_000_000}MB jaw=${r.jawVertices}")
        for (j in AutoRig3D.NAMES.indices) println("  ${AutoRig3D.NAMES[j]} ${r.jointsWorld.slice(j * 3 until j * 3 + 3).joinToString { "%.3f".format(it) }}")
        println("  mouth ${r.mouth.joinToString { "%.3f".format(it) }}")
        val idx = IntArray(pm.idx.size)
        for (tris in listOf(700_000, 200_000, 50_000, 10_000, 3_000)) { val n = ProgressiveMesh.verticesForTriangles(pm, tris); println("  level $tris → verts=$n tris=${ProgressiveMesh.trim(pm, n, idx)}") }
    }
}
