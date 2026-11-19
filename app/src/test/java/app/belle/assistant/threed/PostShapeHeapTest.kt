package app.belle.assistant.threed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * The stages after the detailed shape (surface: neighbour maps, subdivision, dual-grid mesh; paint; cluster) must not
 * put mesh-sized arrays on the Java heap. Real failure (0.1.3, OUKITEL WP100 TITAN, 3,843 voxels): "Failed to allocate
 * a 151620624 byte allocation … growth limit 536870912" building the surface: Android's per-app heap is 512 MB even
 * with largeHeap on an 11.2 GB phone.
 *
 * This runs those stages at the size of that phone's model (≈ 700 k surface voxels at the last level, a 150 k-voxel
 * level before it with 32 + 32 channels per child, i.e. 150 MB tensors like the one that failed) and measures how much
 * Java heap the thread allocates. Everything mesh-sized is in [Big] (native memory), so the whole chain must allocate
 * less than [BUDGET_MB] of Java heap in total (the 1024² z-buffer, 4 MB, is the largest Java array left).
 */
class PostShapeHeapTest {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocated() = mx.getThreadAllocatedBytes(Thread.currentThread().id)

    /** A sphere shell of voxels at [res]³, about [target] voxels. */
    private fun shell(target: Int, res: Int = 512): Big {
        val r = Math.sqrt(target / (4 * Math.PI * 2.0)); val c = res / 2.0; val list = Big.ints(target * 2 * 3); val l = list.i; var n = 0
        for (x in 0 until res) for (y in 0 until res) {
            val dxy = (x - c) * (x - c) + (y - c) * (y - c); if (dxy > (r + 2) * (r + 2)) continue
            for (z in 0 until res) { val d = Math.sqrt(dxy + (z - c) * (z - c)); if (d >= r - 1 && d < r + 1 && n < target * 2) { l.put(n * 3, x); l.put(n * 3 + 1, y); l.put(n * 3 + 2, z); n++ } }
        }
        return list.prefix(n * 3).also { list.close() }
    }

    @Test fun surfacePaintAndClusterStayOffTheJavaHeap() {
        val img = P3Math.Img(1024, 1024, Array(3) { c -> FloatArray(1024 * 1024) { 0.2f + 0.3f * c } })
        val mask = FloatArray(1024 * 1024) { 1f }
        System.gc()
        val before = allocated(); val nativeBefore = NativeMem.live.get()

        // a level of the decoder: 150 k voxels, 8 children each with 32 + 32 channels (2 × 150 MB outputs)
        val lvl = shell(150_000, 256); val n = lvl.size / 3
        val sub = Big.floats(n * 8).also { b -> for (i in 0 until n * 8) b.f.put(i, if (i % 8 < 4) 1f else -1f) }
        val hc = Big.floats(n * 8 * 32); val h = Big.floats(n * 8 * 32)
        ShapeDecoder.neighbours(lvl).use { nbr -> assertEquals(n * 27, nbr.size) }
        ShapeDecoder.subdivide(lvl, sub, hc, h).use { s -> assertEquals(n * 4 * 3, s.coords.size); assertEquals(n * 4 * 32, s.hg.size) }
        lvl.close(); sub.close(); hc.close(); h.close()

        // the last level and the mesh: ≈ 700 k surface voxels at 512³
        val coords = shell(700_000); val nv = coords.size / 3
        val out = Big.floats(nv * 7).also { b -> for (i in 0 until nv) { for (k in 3..5) b.f.put(i * 7 + k, 1f) } }
        ShapeDecoder.neighbours(coords).close()
        val mesh = ShapeDecoder.toMesh(coords, out, 512, 3843); coords.close(); out.close()
        assertTrue("mesh has triangles: ${mesh.nt}", mesh.nt > 500_000)
        TextureBake.bake(mesh.vertices, mesh.triangles, img, mask, 40.0).use { r ->
            TextureBake.cluster(mesh.vertices, r.colors, mesh.triangles, 256).use { c -> assertTrue(c.idx.size > 0) }
        }
        mesh.close()

        val heapMb = (allocated() - before) / 1e6
        println("post-shape chain: $nv surface voxels, ${"%.1f".format(heapMb)} MB Java heap allocated, native peak ${NativeMem.peak.get() shr 20} MB")
        assertTrue("Java heap allocated by the surface/paint/cluster chain: ${"%.1f".format(heapMb)} MB (budget $BUDGET_MB MB)", heapMb < BUDGET_MB)
        assertEquals("every native buffer was freed", nativeBefore, NativeMem.live.get())
    }

    companion object { const val BUDGET_MB = 64.0 }
}
