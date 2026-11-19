package app.belle.assistant.threed

import app.belle.assistant.core.CheckpointStore
import app.belle.assistant.core.CheckpointStore.Companion.floats
import app.belle.assistant.core.CheckpointStore.Companion.ints
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What a photo → 3D run has finished so far, saved after every stage and every sampling step (atomic, checksummed,
 * two slots per stage: see [CheckpointStore]). After a kill, a dead battery or a reboot the run continues from the newest
 * valid checkpoint; a damaged one is skipped and the stage before it is used (worst case the run starts over).
 *   cond   crop (3×S²), crop mask (S²), FOV, DINOv3-512 global + patch features     (~20 MB at S = 1024)
 *   ss     structure latent after step k (32×16³)
 *   vox    structure voxels (x,y,z triples)
 *   shape  shape latent after step k (N×32)
 *   glb    the finished model, before it is given to the assistant
 */
class Pixal3DCheckpoint(dir: File) {
    val store = CheckpointStore(dir)

    class CondSnap(val fov: Double, val crop: P3Math.Img, val cropMask: FloatArray, val global: FloatArray, val patches: FloatArray)

    fun saveCond(fov: Double, crop: P3Math.Img, cropMask: FloatArray, d: Pixal3D.Dino) {
        val o = ByteArrayOutputStream(); DataOutputStream(o).use { w ->
            w.writeDouble(fov); w.writeInt(crop.w); w.writeInt(crop.h); w.writeInt(d.size); w.writeInt(d.global.size); w.writeInt(d.patches.size)
            for (c in 0 until 3) w.write(floats(crop.px[c])); w.write(floats(cropMask)); w.write(floats(d.global)); w.write(floats(d.patches)) }
        store.save("cond", 0, o.toByteArray())
    }
    fun cond(): Pair<CondSnap, Int>? = runCatching {
        val (_, b) = store.load("cond") ?: return null
        DataInputStream(b.inputStream()).use { r ->
            val fov = r.readDouble(); val w = r.readInt(); val h = r.readInt(); val size = r.readInt(); val ng = r.readInt(); val np = r.readInt()
            var off = 8 + 5 * 4; val px = w * h
            val img = P3Math.Img(w, h, Array(3) { floats(b, off, px).also { off += px * 4 } })
            val m = floats(b, off, px); off += px * 4
            val g = floats(b, off, ng); off += ng * 4
            val p = floats(b, off, np)
            CondSnap(fov, img, m, g, p) to size
        }
    }.getOrNull()

    fun saveSs(stepsDone: Int, x: FloatArray) = store.save("ss", stepsDone, floats(x))
    fun ss(): Pair<Int, FloatArray>? = store.load("ss")?.let { (k, b) -> k to floats(b) }
    fun saveVox(coords: IntArray) = store.save("vox", 0, ints(coords))
    fun vox(): IntArray? = store.load("vox")?.second?.let(::ints)
    fun saveShape(stepsDone: Int, x: FloatArray) = store.save("shape", stepsDone, floats(x))
    fun shape(): Pair<Int, FloatArray>? = store.load("shape")?.let { (k, b) -> k to floats(b) }
    fun saveGlb(glb: ByteArray) = store.save("glb", 0, glb)
    fun glb(): ByteArray? = store.load("glb")?.second
    /** The furthest point a resumed run starts from, in words (for the log and the UI). */
    fun describe(structureSteps: Int, shapeSteps: Int): String = when {
        glb() != null -> "the finished model"
        shape() != null -> "detailed shape step ${shape()!!.first}/$shapeSteps"
        vox() != null -> "the rough shape"
        ss() != null -> "rough shape step ${ss()!!.first}/$structureSteps"
        cond() != null -> "the prepared photo"
        else -> "the start"
    }
}
