package app.belle.assistant.threed

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The app's whole Pixal3D run (Pixal3D.generateFrom: crop → MoGe-2 → DINOv3 → structure → shape 512 → decoder → bake →
 * cluster → glb) on the desktop JVM, through the app's own JNI ORT runner built for the host against ONNX Runtime 1.28
 * (the phone's version). Opt-in: -Dp3d.e2e=1 with P3D_LIBS=<dir with libbelle_ort.so + libonnxruntime.so>.
 * -Dp3d.threads=4 (phone-like). Writes build/p3d_e2e.{json,glb} and the unclustered mesh as .f32/.i32.
 */
class Pixal3DEndToEndTest {
    @Test fun photoToGlb() {
        assumeTrue(System.getProperty("p3d.e2e") == "1")
        val models = File(System.getProperty("p3d.models") ?: "/workspace/port3d/phone")
        val bi = ImageIO.read(File(System.getProperty("p3d.image") ?: "../art/dragoness.png")); val w = bi.width; val h = bi.height
        val img = P3Math.Img(w, h, Array(3) { c -> FloatArray(w * h) { i -> ((bi.getRGB(i % w, i / w) shr (16 - 8 * c)) and 255) / 255f } })
        // the reference run's subject mask (u2netp matting in ComfyUI), same size as the image
        val mf = File(System.getProperty("p3d.mask") ?: "/workspace/port3d/ref/mask1024.u8").readBytes()
        val mask = FloatArray(mf.size) { (mf[it].toInt() and 255) / 255f }
        val threads = (System.getProperty("p3d.threads") ?: "4").toInt()
        val p3 = Pixal3D(models, threads).apply { keepMesh = true }
        val g = p3.generateFrom(img, mask, progress = { what, f -> println("p3d ${"%.0f".format(f * 100)}% $what rss ${Port3D.rssMb()} MB"); true })
        File("build").mkdirs(); File("build/p3d_e2e.glb").writeBytes(g.glb)
        val (mv, mt) = p3.lastMesh!!
        File("build/p3d_e2e_mesh.f32").writeBytes(ByteBuffer.allocate(mv.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(mv) }.array())
        File("build/p3d_e2e_mesh.i32").writeBytes(ByteBuffer.allocate(mt.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(mt) }.array())
        val j = org.json.JSONObject().put("threads", threads).put("ms", g.ms).put("peak_mb", g.peakMb).put("voxels", g.voxels).put("fov", g.fovDeg)
            .put("mesh_vertices", mv.size / 3).put("mesh_triangles", mt.size / 3).put("native_peak_mb", NativeMem.peak.get() shr 20).put("java_heap_max_mb", Runtime.getRuntime().maxMemory() shr 20).put("glb_vertices", g.vertices).put("glb_triangles", g.triangles).put("seen", g.seen).put("filled", g.filled)
            .put("stages", org.json.JSONArray().apply { g.stats.forEach { put(it.json()) } })
        File("build/p3d_e2e.json").writeText(j.toString(1)); println(j.toString(1))
    }
}
