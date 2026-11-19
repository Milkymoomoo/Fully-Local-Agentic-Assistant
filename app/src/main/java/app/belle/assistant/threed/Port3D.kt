package app.belle.assistant.threed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import kotlin.math.exp

/**
 * On-phone Pixal3D port, stage by stage (see tools/pixal3d_port/README.md). Each stage records wall time and the
 * process's peak resident memory while it ran, so the table in the README can be filled from real devices.
 */
object Port3D {
    /** [peakMb] process RSS (native + Java + mapped), [heapMb] peak Java heap in use, [nativeBufMb] peak [NativeMem] buffers. */
    data class Stat(val stage: String, val ms: Long, val peakMb: Int, val baseMb: Int, val note: String = "", val heapMb: Int = 0, val nativeBufMb: Int = 0) {
        fun json() = JSONObject().put("stage", stage).put("ms", ms).put("peak_mb", peakMb).put("base_mb", baseMb).put("note", note)
            .put("heap_mb", heapMb).put("native_buf_mb", nativeBufMb)
    }
    fun heapMb(): Int = Runtime.getRuntime().let { ((it.totalMemory() - it.freeMemory()) shr 20).toInt() }

    fun rssMb(): Int = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toInt() / 1024 }.getOrDefault(-1)

    /** Runs [block] while sampling RSS, Java heap and native buffers every 25 ms. */
    fun <T> measure(stage: String, note: String = "", block: () -> T): Pair<T, Stat> {
        val base = rssMb(); val peak = java.util.concurrent.atomic.AtomicInteger(base); val on = java.util.concurrent.atomic.AtomicBoolean(true)
        val heap = java.util.concurrent.atomic.AtomicInteger(heapMb()); val nb = java.util.concurrent.atomic.AtomicLong(NativeMem.live.get())
        val t = Thread { while (on.get()) { peak.accumulateAndGet(rssMb(), ::maxOf); heap.accumulateAndGet(heapMb(), ::maxOf); nb.accumulateAndGet(NativeMem.live.get(), ::maxOf); Thread.sleep(25) } }.apply { isDaemon = true; start() }
        val t0 = System.currentTimeMillis()
        try { val r = block(); return r to Stat(stage, System.currentTimeMillis() - t0, maxOf(peak.get(), rssMb()), base, note, maxOf(heap.get(), heapMb()), (maxOf(nb.get(), NativeMem.live.get()) shr 20).toInt()) }
        finally { on.set(false); t.join(100) }
    }

    fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Square RGB → CHW float in [0,1] (BiRefNet: mean 0, std 1). */
    fun chw(b: Bitmap, s: Int, mean: FloatArray = floatArrayOf(0f, 0f, 0f), std: FloatArray = floatArrayOf(1f, 1f, 1f)): FloatArray {
        val r = Bitmap.createScaledBitmap(b, s, s, true); val px = IntArray(s * s); r.getPixels(px, 0, s, 0, 0, s, s)
        val out = FloatArray(3 * s * s)
        for (i in px.indices) { val c = px[i]
            out[i] = (Color.red(c) / 255f - mean[0]) / std[0]; out[s * s + i] = (Color.green(c) / 255f - mean[1]) / std[1]; out[2 * s * s + i] = (Color.blue(c) / 255f - mean[2]) / std[2] }
        return out
    }

    /** BiRefNet background removal: returns the alpha mask (0..1) at the model size, and the stage stats. */
    fun birefnet(model: File, img: Bitmap, size: Int = 512): Pair<FloatArray, List<Stat>> {
        val (sess, load) = measure("birefnet load") { Ort.Session(model.path, threads()) }
        sess.use { s ->
            val x = chw(img, size)
            val (out, run) = measure("birefnet run ${size}px") { s.run(mapOf(s.inputs[0] to (x to longArrayOf(1, 3, size.toLong(), size.toLong())))) }
            val logits = out.values.first().data
            return FloatArray(logits.size) { 1f / (1f + exp(-logits[it])) } to listOf(load, run)
        }
    }

    /** DINOv3-L features at [size] px (ImageNet normalisation). */
    fun dinov3(model: File, img: Bitmap, size: Int = 512): Pair<Ort.Tensor, List<Stat>> {
        val (sess, load) = measure("dinov3 load") { Ort.Session(model.path, threads()) }
        sess.use { s ->
            val x = chw(img, size, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
            val (out, run) = measure("dinov3 run ${size}px") { s.run(mapOf(s.inputs[0] to (x to longArrayOf(1, 3, size.toLong(), size.toLong())))) }
            return out.values.first() to listOf(load, run)
        }
    }

    fun maskBitmap(m: FloatArray, s: Int): Bitmap = Bitmap.createBitmap(IntArray(s * s) { val v = (m[it] * 255).toInt().coerceIn(0, 255); Color.rgb(v, v, v) }, s, s, Bitmap.Config.ARGB_8888)

    fun dir(ctx: Context) = File(ctx.filesDir, "models/3d").apply { mkdirs() }
}
