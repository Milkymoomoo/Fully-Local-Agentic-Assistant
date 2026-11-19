package app.belle.assistant.threed

import android.graphics.Bitmap
import java.io.File

/**
 * On-phone Pixal3D, stage by stage. Stage 1 (this file so far): conditioning — crop to the subject, DINOv3-L tokens at
 * two sizes, NAF query maps, and the per-voxel projected features each sampling stage needs.
 * Models live in files/models/3d (see tools/pixal3d_port/README.md for how each ONNX file is made and checked).
 */
class Pixal3D(private val dir: File, private val threads: Int = Port3D.threads()) {
    val stats = mutableListOf<Port3D.Stat>()

    /** DINOv3 tokens for one size: the 5 global tokens [5×1024] and the patch grid [1024 × n × n]. */
    class Dino(val size: Int, val global: FloatArray, val patches: FloatArray) { val n = size / 16 }
    /** NAF query map for a stage: [256 × H × H] (native memory: 268 MB at 512 px) and its block average k [256 × n × n]. */
    class NafQ(val h: Int, val q: Big, val k: FloatArray, val n: Int) : AutoCloseable { override fun close() = q.close() }

    class Cond(val fovDeg: Double, val crop: P3Math.Img, val d512: Dino, val d1024: Dino?) : AutoCloseable {
        val qMaps = HashMap<String, NafQ>()
        fun drop(stage: String) { qMaps.remove(stage)?.close() }
        override fun close() { qMaps.values.forEach { it.close() }; qMaps.clear() }
    }

    private fun <T> stage(name: String, block: () -> T): T { val (r, s) = Port3D.measure(name, block = block); stats += s; return r }
    private fun session(name: String) = stage("load $name") { Ort.Session(File(dir, name).path, threads) }

    fun imgOf(b: Bitmap): P3Math.Img {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        return P3Math.Img(w, h, Array(3) { c -> FloatArray(w * h) { i -> ((px[i] shr (16 - 8 * c)) and 255) / 255f } })
    }

    /** ImageCropToMask(pad 1.1, black) at [size]. [mask] 0..1 at the bitmap size (e.g. from Cutout.matte). */
    fun crop(b: Bitmap, mask: FloatArray, size: Int = 1024) = stage("crop") { P3Math.cropToMask(imgOf(b), mask, size) }

    fun dino(sess: Ort.Session, crop: P3Math.Img, size: Int): Dino = stage("dinov3 ${size}px") {
        val img = P3Math.lanczos(crop, size, size)
        val x = P3Math.chw(img, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
        val (c, s) = P3Math.dinoRope(size); val n = size / 16; val np = n * n
        val out = sess.run(mapOf("pixels" to (x to longArrayOf(1, 3, size.toLong(), size.toLong())),
            "cos" to (c to longArrayOf(np.toLong(), 64)), "sin" to (s to longArrayOf(np.toLong(), 64))))["tokens"]!!.data
        val pre = out.size / 1024 - np
        val g = out.copyOfRange(0, pre * 1024)
        val p = FloatArray(1024 * np); for (t in 0 until np) { val o = (pre + t) * 1024; for (ch in 0 until 1024) p[ch * np + t] = out[o + ch] }
        Dino(size, g, p)
    }

    /** NAF query map for an image of [size] px pooled by [r] (stage targets: shape_512 = 512/1, shape_1024 = 1024/2, tex_1024 = 1024/1). */
    fun nafQ(crop: P3Math.Img, size: Int, r: Int, lrN: Int): NafQ = stage("naf q ${size}/$r") {
        Ort.Session(File(dir, "naf_q_r$r.onnx").path, threads).use { sess ->
            val img = P3Math.lanczos(crop, size, size); val h = size / r; val (c, s) = P3Math.nafRope(h)
            val out = sess.runBig(mapOf("image" to (P3Math.chw(img) to longArrayOf(1, 3, size.toLong(), size.toLong())),
                "cos" to (c to longArrayOf((h * h).toLong(), 64)), "sin" to (s to longArrayOf((h * h).toLong(), 64))))
            out.filterKeys { it != "q" }.values.forEach { it.close() }
            val q = out.getValue("q").data
            try { NafQ(h, q, P3Math.avgPool(q.f, 256, h, h, h / lrN), lrN) } catch (e: Throwable) { q.close(); throw e }
        }
    }

    /** MoGe-2 FOV of the crop (moge2_l_<tokens>.onnx, fixed token grid b×b, input b·14 px). */
    fun fov(crop: P3Math.Img, tokens: Int = 3600): Double = stage("moge2 fov") {
        Ort.Session(pick("moge2_l_${tokens}_nb8.onnx", "moge2_l_$tokens.onnx").path, threads).use { sess ->
            val b = Math.round(Math.sqrt(tokens.toDouble())).toInt(); val s = b * 14
            val x = P3Math.chw(P3Math.resizeAA(crop, s, s))
            val out = sess.run(mapOf("image" to (x to longArrayOf(1, 3, s.toLong(), s.toLong()))))
            val p = out["points"]!!; val h = p.shape[2].toInt(); val w = p.shape[3].toInt()
            P3Math.mogeFov(p.data, out["mask"]!!.data, h, w, crop.w)
        }
    }

    /** Stage 1: DINOv3 at 512 and (unless [only512]) 1024, freeing the model before the NAF maps are built. */
    fun condition(crop: P3Math.Img, fovDeg: Double, only512: Boolean = false, sizes: IntArray = intArrayOf(512, 1024)): Cond {
        val (d512, d1024) = stage("load dinov3") { Ort.Session(pick("dinov3_l_nb8.onnx", "dinov3_l.onnx").path, threads) }.use { s -> dino(s, crop, sizes[0]) to (if (only512) null else dino(s, crop, sizes[1])) }
        return Cond(fovDeg, crop, d512, d1024)
    }

    /** SS stage: LR DINO-512 features at every cell of the dense 16³ grid → [4096 × 1024]. */
    fun ssProj(c: Cond): FloatArray = stage("proj ss") {
        val uv = P3Math.uvNdc(P3Math.denseCoords(16), 16, c.fovDeg, c.d512.size)
        FloatArray(4096 * 1024).also { P3Math.sampleMap(c.d512.patches, 1024, c.d512.n, c.d512.n, uv, it, 1024, 0) }
    }

    /**
     * Sparse stages: [coords] (x,y,z triples) at grid [res] → [N × 2048] = LR DINO features ‖ NAF high-res features.
     * stage: "shape_512" (DINO-512, NAF 512 px), "shape_1024" (DINO-1024, NAF 512 px), "tex_1024" (DINO-1024, NAF 1024 px).
     */
    fun sparseProj(c: Cond, stage: String, coords: IntArray, res: Int, nafSizes: Map<String, Int> = mapOf()): FloatArray = stage("proj $stage") {
        val d = if (stage == "shape_512") c.d512 else c.d1024 ?: error("$stage needs the 1024 px DINO features")
        val imgRes = d.size
        val (size, r) = when (stage) { "shape_512" -> d.size to 1; "shape_1024" -> d.size to 2; else -> d.size to 1 }
        val q = c.qMaps.getOrPut(stage) { nafQ(c.crop, nafSizes[stage] ?: size, r, d.n) }
        val uv = P3Math.uvNdc(coords, res, c.fovDeg, imgRes); val n = coords.size / 3
        val out = FloatArray(n * 2048)
        P3Math.sampleMap(d.patches, 1024, d.n, d.n, uv, out, 2048, 0)
        P3Math.sampleNaf(q.q.f, q.h, q.h, q.k, d.patches, 1024, d.n, d.n, uv, out, 2048, 1024)
        out
    }

    /** Voxels of the 32³ structure: (x, y, z) triples in the order ComfyUI's argwhere gives them (x-major). */
    class Voxels(val coords: IntArray, val res: Int) { val n get() = coords.size / 3 }

    /**
     * Stage 2: structure flow (pixal3d_ss_flow.onnx, int8 ConvRot) sampled with [FlowSampler.Params.STRUCTURE] over a
     * [1×32×16³] latent (the model sees the first 8 channels), then the TRELLIS.2 structure decoder
     * (trellis2_struct_dec.onnx: 64³ logits) and maxpool 2 → 32³ occupancy. Uncond = zero global tokens and proj.
     */
    fun structure(c: Cond, seed: Long = 56, noise: FloatArray? = null, p: FlowSampler.Params = FlowSampler.Params.STRUCTURE,
                  onStep: (Int, Double, Double) -> Boolean = { _, _, _ -> true }, ck: Pixal3DCheckpoint? = null): Voxels {
        val proj = ssProj(c); val g = c.d512.global
        val g0 = FloatArray(g.size); val p0 = FloatArray(proj.size); val L = (g.size / 1024).toLong()
        val lat = session("pixal3d_ss_flow.onnx").use { s ->
            stage("ss flow ${p.steps} steps") {
                FlowSampler.sample(noise ?: FlowSampler.noise(32 * 4096, seed), p, { x, t, cond ->
                    val v = s.run(mapOf("x" to (x.copyOfRange(0, 8 * 4096) to longArrayOf(1, 8, 16, 16, 16)),
                        "t" to (floatArrayOf(t) to longArrayOf(1)),
                        "cond_global" to ((if (cond) g else g0) to longArrayOf(1, L, 1024)),
                        "proj" to ((if (cond) proj else p0) to longArrayOf(1, 4096, 1024))))["v"]!!.data
                    v.copyOf(32 * 4096)   // padding channels: v = 0, so x0 = x there (as ComfyUI pads the output)
                }, onStep, resume = ck?.ss()?.takeIf { it.second.size == 32 * 4096 }, onState = { k, x -> ck?.saveSs(k, x) })
            }
        }
        return decodeStructure(lat.copyOfRange(0, 8 * 4096))
    }

    /** [latent] 8×16³ → 32³ voxels (decoder logits 64³ > 0, then 2× max pool). */
    fun decodeStructure(latent: FloatArray): Voxels {
        val logits = session("trellis2_struct_dec.onnx").use { s ->
            stage("struct decode") { s.run(mapOf("latent" to (latent to longArrayOf(1, 8, 16, 16, 16))))["logits"]!!.data } }
        return Voxels(dropFloaters(occupancy(logits, 64), 32), 32)
    }

    /**
     * Stage 3a: first shape pass (pixal3d_shape512_flow.onnx) over the structure voxels with
     * [FlowSampler.Params.SHAPE_512]. Returns the shape latent [N × 32] (token-major), not yet denormalised.
     */
    fun shape512(c: Cond, vox: Voxels, seed: Long = 42, noise: FloatArray? = null, p: FlowSampler.Params = FlowSampler.Params.SHAPE_512,
                 onStep: (Int, Double, Double) -> Boolean = { _, _, _ -> true }, ck: Pixal3DCheckpoint? = null): FloatArray {
        val n = vox.n
        // a resumed run whose detailed shape was already finished: nothing to sample, so don't rebuild the 268 MB NAF map
        // and the projection either (0.1.3 did, and a resumed run died there before it reached the surface)
        ck?.shape()?.takeIf { it.second.size == n * 32 && it.first >= p.steps }?.let { return it.second }
        val proj = try { sparseProj(c, "shape_512", vox.coords, vox.res) } finally { c.drop("shape_512") }   // the NAF map is only needed for the projection
        val g = c.d512.global
        val g0 = FloatArray(g.size); val p0 = FloatArray(proj.size); val L = (g.size / 1024).toLong()
        val co = FloatArray(n * 3) { vox.coords[it].toFloat() }
        return session("pixal3d_shape512_flow.onnx").use { s ->
            stage("shape512 flow ${p.steps} steps N=$n") {
                FlowSampler.sample(noise ?: FlowSampler.noise(n * 32, seed), p, { x, t, cond ->
                    s.run(mapOf("x" to (x to longArrayOf(n.toLong(), 32)), "coords" to (co to longArrayOf(n.toLong(), 3)),
                        "t" to (floatArrayOf(t) to longArrayOf(1)),
                        "cond_global" to ((if (cond) g else g0) to longArrayOf(1, L, 1024)),
                        "proj" to ((if (cond) proj else p0) to longArrayOf(n.toLong(), 2048))))["v"]!!.data
                }, onStep, resume = ck?.shape()?.takeIf { it.second.size == n * 32 }, onState = { k, x -> ck?.saveShape(k, x) })
            }
        }
    }

    /** The first of [names] present in the model folder (smaller int8 variants first). */
    fun pick(vararg names: String): File = names.map { File(dir, it) }.firstOrNull { it.exists() } ?: File(dir, names.last())

    class Generated(val glb: ByteArray, val vertices: Int, val triangles: Int, val voxels: Int, val fovDeg: Double, val seen: Int, val filled: Int,
                    val stats: List<Port3D.Stat>, val peakMb: Int, val ms: Long,
                    /** What actually ran (for last_run.json and the per-call timing the next plan uses). */
                    val ssParams: FlowSampler.Params, val shapeParams: FlowSampler.Params, val seedSs: Long, val seedShape: Long, val clusterGrid: Int)
    class Cancelled : RuntimeException("cancelled")
    /** The decoded mesh of the last [generateFrom] before clustering (for checks). */
    var lastMesh: Pair<FloatArray, IntArray>? = null
    /** Keep a copy of the decoded mesh in [lastMesh] (desktop tests only: it is big). */
    var keepMesh = false

    /**
     * The whole on-phone photo → 3D model run: subject mask (BiRefNet 512, else [fallbackMask]) → 1024 crop → MoGe-2 FOV →
     * DINOv3 512 → structure flow + decoder (32³ voxels) → shape flow 512 → shape VAE decoder (mesh) → colour from the
     * photo ([TextureBake]) → [cluster]³ vertex clustering → .glb (vertex colours) the avatar importer takes.
     * Each model is loaded for its stage only. [progress](what, 0..1) returns false to cancel.
     * Not in this version: the 1024/1536 refinement pass and the TRELLIS.2 texture model (see tools/pixal3d_port/README.md).
     */
    fun generate(src: Bitmap, fallbackMask: ((Bitmap) -> FloatArray?)? = null, plan: Run3DPlan.Plan = DEFAULT_PLAN,
                 progress: (String, Float) -> Boolean = { _, _ -> true }, steps: Int = 0, ck: Pixal3DCheckpoint? = null,
                 random: () -> Long = { java.util.Random().nextInt(Int.MAX_VALUE).toLong() }): Generated {
        val t0 = System.currentTimeMillis(); stats.clear()
        fun step(what: String, f: Float) { if (!progress(what, f)) throw Cancelled() }
        // resumed run with the prepared photo checkpointed: no need to cut the subject out again
        if (ck?.cond()?.takeIf { it.first.crop.w == plan.cropSize } != null) return generateFrom(null, null, plan, progress, t0, steps, random, ck)
        step("Finding the subject", 0.01f)
        val w = src.width; val h = src.height
        val bir = File(dir, "birefnet_512.onnx")
        // the workflow's background switch off = use the picture's own transparency (if it really has some)
        val own = if (!plan.removeBackground) alphaMask(src) else null
        val mask: FloatArray = own ?: if (bir.exists()) {
            val (m, st) = Port3D.birefnet(bir, src, 512); stats += st
            FloatArray(w * h) { i -> val x = (i % w + 0.5f) * 512f / w - 0.5f; val y = (i / w + 0.5f) * 512f / h - 0.5f
                val x0 = x.toInt().coerceIn(0, 511); val y0 = y.toInt().coerceIn(0, 511); val x1 = (x0 + 1).coerceAtMost(511); val y1 = (y0 + 1).coerceAtMost(511)
                val tx = (x - x0).coerceIn(0f, 1f); val ty = (y - y0).coerceIn(0f, 1f)
                (m[y0 * 512 + x0] * (1 - tx) + m[y0 * 512 + x1] * tx) * (1 - ty) + (m[y1 * 512 + x0] * (1 - tx) + m[y1 * 512 + x1] * tx) * ty }
        } else fallbackMask?.invoke(src) ?: error("no background-removal model")
        return generateFrom(imgOf(src), mask, plan, progress, t0, steps, random, ck)
    }

    /** The picture's alpha as a mask, if it has a real cut-out (some clearly transparent and some clearly opaque pixels). */
    fun alphaMask(b: Bitmap): FloatArray? {
        if (!b.hasAlpha()) return null
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val m = FloatArray(w * h) { ((px[it] ushr 24) and 255) / 255f }
        return m.takeIf { a -> a.count { it < 0.1f } > a.size / 50 && a.count { it > 0.9f } > a.size / 50 }
    }

    /**
     * [generate] from an RGB image and its subject mask (same size); also the entry point of the desktop end-to-end test.
     * With [ck], every stage and sampling step is checkpointed and a run that was killed continues where it stopped
     * ([srcImg] and [mask] may then be null when the prepared photo is in the checkpoint).
     */
    fun generateFrom(srcImg: P3Math.Img?, mask: FloatArray?, plan: Run3DPlan.Plan = DEFAULT_PLAN,
                     progress: (String, Float) -> Boolean = { _, _ -> true }, t0: Long = System.currentTimeMillis(), steps: Int = 0,
                     random: () -> Long = { java.util.Random().nextInt(Int.MAX_VALUE).toLong() }, ck: Pixal3DCheckpoint? = null): Generated {
        fun step(what: String, f: Float) { if (!progress(what, f)) throw Cancelled() }
        val cs = plan.cropSize; val pad = plan.cropPadding
        val saved = ck?.cond()?.takeIf { it.first.crop.w == cs }
        val crop: P3Math.Img; val cropMask: FloatArray; val c: Cond
        if (saved != null) {
            val (sn, size) = saved
            crop = sn.crop; cropMask = sn.cropMask
            step("Looking at the picture", 0.07f)
            c = Cond(sn.fov, crop, Dino(size, sn.global, sn.patches), null)
        } else {
            requireNotNull(srcImg); requireNotNull(mask)
            val w = srcImg.w; val h = srcImg.h
            crop = stage("crop") { P3Math.cropToMask(srcImg, mask, cs, pad) }
            cropMask = stage("crop mask") { P3Math.cropToMask(P3Math.Img(w, h, Array(3) { mask }), mask, cs, pad).px[0] }
            step("Measuring the camera", 0.04f)
            val fov = fov(crop)
            step("Looking at the picture", 0.07f)
            c = condition(crop, fov, only512 = true)
            ck?.saveCond(fov, crop, cropMask, c.d512)
        }
        val fov = c.fovDeg
        // the run's sampler settings come from the plan (imported workflow, fitted to this phone, plus your overrides);
        // steps > 0: a test run with that many sampling steps per stage (same models, memory and code path; not a usable model)
        val (ss, sh, seeds) = samplerInputs(plan, steps, random); val (seedSs, seedShape) = seeds
        step("Rough shape", 0.10f)   // stage boundary for the time-left estimate (Pixal3DEta)
        val vox = ck?.vox()?.let { Voxels(it, 32) } ?: structure(c, seedSs, p = ss, ck = ck, onStep = { i, _, _ -> progress("Rough shape ${i + 1}/${ss.steps}", 0.10f + 0.40f * (i + 1) / ss.steps) })
            .also { ck?.saveVox(it.coords) }
        step("Detailed shape (${vox.n} voxels)", 0.50f)
        val lat = shape512(c, vox, seedShape, p = sh, ck = ck, onStep = { i, _, _ -> progress("Detailed shape ${i + 1}/${sh.steps}", 0.50f + 0.35f * (i + 1) / sh.steps) })
        step("Building the surface", 0.86f)
        c.close()   // nothing after the shape stage needs the photo features
        // From here every mesh-sized array is native memory ([Big]), freed as soon as the next sub-step has its copy:
        // the Java heap (256–512 MB on Android whatever the RAM) only ever holds the photo and the final phone-sized mesh.
        val mesh = ShapeDecoder(dir, threads) { stats += it }.decode(lat, vox.coords)
        var grid = 256; var cp = FloatArray(0); var cc: FloatArray? = null; var ct = IntArray(0); var seen = 0; var filled = 0
        try {
            if (keepMesh) lastMesh = mesh.vertices.toFloatArray() to mesh.triangles.toIntArray()
            step("Painting it from the photo", 0.94f)
            val r = stage("texture bake ${mesh.nv} verts") { TextureBake.bake(mesh.vertices, mesh.triangles, crop, cropMask, fov) }
            seen = r.seen; filled = r.filled
            try {
                step("Making it phone-sized", 0.97f)
                // vertex clustering at 256³, coarser until the workflow's (fitted) triangle target is met
                var out = stage("cluster $grid") { TextureBake.cluster(mesh.vertices, r.colors, mesh.triangles, grid) }
                try {
                    repeat(4) {
                        val tri = out.idx.size / 3
                        if (tri <= plan.maxTriangles || grid <= 48) return@repeat
                        grid = (grid * kotlin.math.sqrt(plan.maxTriangles.toDouble() / tri) * 0.97).toInt().coerceAtLeast(48)
                        out.close(); out = stage("cluster $grid") { TextureBake.cluster(mesh.vertices, r.colors, mesh.triangles, grid) }
                    }
                    // the phone-sized result (≤ the plan's triangle target) is small enough for ordinary arrays
                    val (a, b, d) = out.toArrays(); cp = a; cc = b; ct = d
                } finally { out.close() }
            } finally { r.close() }
        } finally { mesh.close(); Ort.trim() }
        val glb = stage("write glb") { app.belle.assistant.mesh.Glb.write(app.belle.assistant.mesh.Mesh3D(cp, null, null, cc, null, null, ct, null, null)) }
        step("Done", 1f)
        return Generated(glb, cp.size / 3, ct.size / 3, vox.n, fov, seen, filled, stats.toList(), stats.maxOf { it.peakMb }, System.currentTimeMillis() - t0,
            ss, sh, seedSs, seedShape, grid)
    }

    companion object {
        /** The sampler parameters and seeds the two flow stages get from [plan] (unit tested: workflow → sampler). */
        fun samplerInputs(plan: Run3DPlan.Plan, steps: Int = 0, random: () -> Long = { java.util.Random().nextInt(Int.MAX_VALUE).toLong() }):
                Triple<FlowSampler.Params, FlowSampler.Params, Pair<Long, Long>> {
            val ss = plan.structure.params().let { if (steps > 0) it.copy(steps = steps) else it }
            val sh = plan.shape.params().let { if (steps > 0) it.copy(steps = steps) else it }
            return Triple(ss, sh, plan.structure.seedFor(random) to plan.shape.seedFor(random))
        }
        /** No workflow imported, nothing overridden, a 12 GB phone: the phone defaults (8 + 12 steps, seeds 56 / 42). */
        val DEFAULT_PLAN: Run3DPlan.Plan by lazy { Run3DPlan.plan(app.belle.assistant.core.Workflow3DSettings.PHONE_DEFAULT, emptyMap(), Run3DPlan.Device.estimate(12.0)) }

        /**
         * Removes small pieces floating apart from the model (26-connected groups of voxels smaller than 5 % of the
         * largest group, at least 8 voxels): with the phone's noise the structure stage sometimes leaves a stray voxel or
         * two above the model, which the shape stage then turns into a floating speck. Keeps the x-major order.
         */
        fun dropFloaters(coords: IntArray, r: Int): IntArray {
            val n = coords.size / 3; if (n == 0) return coords
            val at = IntArray(r * r * r) { -1 }; for (i in 0 until n) at[(coords[i * 3] * r + coords[i * 3 + 1]) * r + coords[i * 3 + 2]] = i
            val comp = IntArray(n) { -1 }; val sizes = ArrayList<Int>(); val stack = IntArray(n)
            for (s in 0 until n) { if (comp[s] >= 0) continue
                val c = sizes.size; var sp = 0; stack[sp++] = s; comp[s] = c; var cnt = 0
                while (sp > 0) { val i = stack[--sp]; cnt++
                    val x = coords[i * 3]; val y = coords[i * 3 + 1]; val z = coords[i * 3 + 2]
                    for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                        val a = x + dx; val b = y + dy; val d = z + dz
                        if (a < 0 || b < 0 || d < 0 || a >= r || b >= r || d >= r) continue
                        val j = at[(a * r + b) * r + d]; if (j >= 0 && comp[j] < 0) { comp[j] = c; stack[sp++] = j } } }
                sizes += cnt }
            val keep = maxOf(8, (sizes.max() * 0.05).toInt())
            val out = ArrayList<Int>(coords.size)
            for (i in 0 until n) if (sizes[comp[i]] >= keep || sizes[comp[i]] == sizes.max()) { out += coords[i * 3]; out += coords[i * 3 + 1]; out += coords[i * 3 + 2] }
            return out.toIntArray()
        }

        /** Occupancy of [r]³ logits after 2× max pooling, as (x,y,z) triples in x-major order. */
        fun occupancy(logits: FloatArray, r: Int): IntArray {
            val h = r / 2; val out = ArrayList<Int>()
            for (x in 0 until h) for (y in 0 until h) for (z in 0 until h) {
                var on = false
                loop@ for (a in 0..1) for (b in 0..1) for (d in 0..1) if (logits[((2 * x + a) * r + 2 * y + b) * r + 2 * z + d] > 0f) { on = true; break@loop }
                if (on) { out += x; out += y; out += z }
            }
            return out.toIntArray()
        }
    }
}
