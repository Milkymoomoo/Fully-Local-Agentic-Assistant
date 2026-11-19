package app.belle.assistant.threed

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The rectified-flow sampler every Pixal3D stage uses, matching ComfyUI's KSampler(euler, "normal") on a
 * ModelSamplingDiscreteFlow model with RescaleCFG (x0 space) and CFGOverride (cfg 1 over the low-sigma end).
 * Pure math: the model is a callback, so the same code runs in JVM tests and on the phone.
 */
object FlowSampler {
    /**
     * @param shift flow shift used for the sigma schedule (ModelSamplingSD3 if the workflow adds one, else the model's 3).
     * @param overrideStart CFGOverride start_percent; its sigma uses [baseShift] because the official workflow
     *   applies CFGOverride before ModelSamplingSD3.
     */
    data class Params(val steps: Int, val shift: Double, val cfg: Double, val rescale: Double,
                      val overrideStart: Double = 1.0, val baseShift: Double = 3.0,
                      /** ComfyUI scheduler: "normal" or "simple" (the two the Pixal3D / TRELLIS 2 workflows use). */
                      val scheduler: String = "normal") {
        companion object {
            /** Structure stage (official Pixal3D workflow: seed 56, 12 steps, shift 5, cfg 7.5, rescale 0.7, cfg 1 from 66.7%). */
            val STRUCTURE = Params(12, 5.0, 7.5, 0.7, 0.667)
            /** First shape pass, img2shape_512 (seed 42, 20 steps, shift 3, cfg 7.5, rescale 0.5, cfg 1 from 76.9%). */
            val SHAPE_512 = Params(20, 3.0, 7.5, 0.5, 0.769)
            /** What the phone runs (2026-11-19, same seeds and models, vs the reference): structure 8 steps = 14 model
             *  calls instead of 21, voxel IoU 0.890 (12 steps: 0.902; 6 steps: 0.861); shape 12 steps = 21 calls instead
             *  of 35, decoded mesh chamfer 0.359 % of the bbox, F@1 % 0.990 (20 steps: 0.36 %, 0.989). */
            val STRUCTURE_PHONE = STRUCTURE.copy(steps = 8)
            val SHAPE_512_PHONE = SHAPE_512.copy(steps = 12)
        }
    }

    fun snrShift(a: Double, t: Double) = a * t / (1 + (a - 1) * t)

    /** Model calls a run of [p] makes: 2 per step while cfg ≠ 1 (cond + uncond), 1 per step after the CFGOverride point. */
    fun modelCalls(p: Params): Int { val sig = sigmas(p.steps, p.shift, p.scheduler); val hi = sigmaHi(p)
        return (0 until p.steps).sumOf { i -> if (sig[i] <= hi || p.cfg == 1.0) 1 else 2 } }

    /** Schedulers this sampler implements exactly (others fall back to "normal", see Run3DPlan). */
    val SCHEDULERS = listOf("normal", "simple")

    /** sigmas for [scheduler] ("normal" or "simple"), then a trailing 0. */
    fun sigmas(steps: Int, shift: Double, scheduler: String): DoubleArray = if (scheduler == "simple") simpleSigmas(steps, shift) else sigmas(steps, shift)

    /** ComfyUI "simple": every (1000/steps)-th of the model's 1000 sigmas from the top, σ_i = shift(1 − ⌊i·1000/steps⌋/1000). */
    fun simpleSigmas(steps: Int, shift: Double): DoubleArray {
        val out = DoubleArray(steps + 1); val ss = 1000.0 / steps
        for (i in 0 until steps) out[i] = snrShift(shift, (1000 - (i * ss).toInt()) / 1000.0)
        return out
    }

    /** sigmas for "normal": timesteps linspace(sigma_max·1000, sigma_min·1000, steps), then a trailing 0. */
    fun sigmas(steps: Int, shift: Double): DoubleArray {
        val tMin = snrShift(shift, 1.0 / 1000) * 1000; val out = DoubleArray(steps + 1)
        for (i in 0 until steps) {
            val t = if (steps == 1) 1000.0 else 1000.0 + (tMin - 1000.0) * i / (steps - 1)
            out[i] = snrShift(shift, t / 1000)
        }
        return out
    }

    /** sigma at or below which CFG is overridden to 1. */
    fun sigmaHi(p: Params) = if (p.overrideStart >= 1.0) 0.0 else if (p.overrideStart <= 0.0) Double.MAX_VALUE else snrShift(p.baseShift, 1 - p.overrideStart)

    private fun std(a: FloatArray): Double {
        var s = 0.0; for (v in a) s += v; val m = s / a.size
        var q = 0.0; for (v in a) { val d = v - m; q += d * d }; return sqrt(q / (a.size - 1))
    }

    /**
     * Runs the sampler from [noise] (scaled by sigma_max = 1). [velocity](x, timestep, cond) returns the model
     * output v at the same size as x (zeros where the model has no channels, e.g. the structure latent's 24
     * padding channels), with x0 = x − σ·v. [onStep] gets (step, sigma, used cfg) after each step; return false to stop.
     */
    /**
     * [resume] = (steps already done, the latent after them) from a checkpoint: sampling continues from there and gives
     * exactly the same result as an uninterrupted run. [onState] gets (steps done, latent) after every step to checkpoint it.
     */
    fun sample(noise: FloatArray, p: Params, velocity: (x: FloatArray, t: Float, cond: Boolean) -> FloatArray,
               onStep: (Int, Double, Double) -> Boolean = { _, _, _ -> true },
               resume: Pair<Int, FloatArray>? = null, onState: (Int, FloatArray) -> Unit = { _, _ -> }): FloatArray {
        val sig = sigmas(p.steps, p.shift, p.scheduler); val hi = sigmaHi(p)
        val start = resume?.first?.coerceIn(0, p.steps) ?: 0
        val x = resume?.second?.also { require(it.size == noise.size) { "checkpoint latent has the wrong size" } }?.copyOf()
            ?: FloatArray(noise.size) { (noise[it] * sig[0]).toFloat() }
        val n = x.size
        for (i in start until p.steps) {
            val sg = sig[i]; val cfg = if (sg <= hi) 1.0 else p.cfg; val t = (sg * 1000).toFloat()
            val vc = velocity(x, t, true)
            val dc = FloatArray(n) { (x[it] - sg * vc[it]).toFloat() }
            val den = if (cfg == 1.0) dc else {
                val vu = velocity(x, t, false)
                val xc = FloatArray(n) { val du = x[it] - sg * vu[it]; (du + cfg * (dc[it] - du)).toFloat() }
                val k = std(dc) / max(std(xc), 1e-8)
                FloatArray(n) { (p.rescale * xc[it] * k + (1 - p.rescale) * xc[it]).toFloat() }
            }
            val ds = sig[i + 1] - sg
            for (j in 0 until n) x[j] = (x[j] + (x[j] - den[j]) / sg * ds).toFloat()
            onState(i + 1, x)
            if (!onStep(i, sg, cfg)) break
        }
        return x
    }

    /** Standard normal noise (the phone can't reproduce torch's generator; results are seed-stable per device). */
    fun noise(n: Int, seed: Long): FloatArray { val r = java.util.Random(seed); return FloatArray(n) { r.nextGaussian().toFloat() } }
}
