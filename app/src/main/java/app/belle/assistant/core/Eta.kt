package app.belle.assistant.core

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

/**
 * Time-left estimates in plain words ("about 1 h 12 min left", "<1 min left"), for the photo → 3D run and for the
 * model download queue. Pure Kotlin (only org.json for the saved timings), so a desktop port can reuse it as is.
 */
object Eta {
    /**
     * null = not enough data yet. Rounded UP, coarser as it gets longer (an estimate is never more precise than it
     * can be): whole minutes under 10 min, 5 min steps under 2 h, 10 min steps above. "about 7 min left" never turns
     * into a surprise 8th.
     */
    fun format(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds < 0) return "estimating time left…"
        if (seconds < 60) return "<1 min left"
        val exact = ceil(seconds / 60.0).toLong()
        val step = when { exact < 10 -> 1L; exact < 120 -> 5L; else -> 10L }
        val min = (exact + step - 1) / step * step
        return if (min < 60) "about $min min left" else "about ${min / 60} h ${min % 60} min left".replace(" 0 min", "")
    }

    /**
     * A phone vs the desktop reference box, for the very first estimate before this phone has timed a step.
     * Real-phone reports (0.1.3, OUKITEL WP100 TITAN, official workflow: 12 rough + 20 detailed steps): the 12 rough steps took
     * about 43 min (3.5 min on average, up to 5.5 min per step) against about 35 s per step on the desktop box, so 6–9.5×
     * slower; detailed steps took about 3 min (box: 12 s). Rounded up to 10 because the first estimate should lean long.
     */
    const val PHONE_VS_DESKTOP = 10.0

    /** Exponentially smoothed average; the first sample is taken as is. */
    class Smooth(private val alpha: Double, start: Double? = null) {
        var value: Double? = start; private set
        fun add(x: Double): Double { val v = value; value = if (v == null) x else v + alpha * (x - v); return value!! }
    }
}

/**
 * The photo → 3D run's time left.
 *
 * Raw estimate ([remainingSec]): every stage has a cost from [prior] (this phone's saved times if it has any, else a
 * first guess from the run plan). Once steps of the running sampling stage have been timed, their measured
 * seconds per step take over (weight 0.75 after one step, 0.9 after two, 0.95 after more). The ratio
 * measured ÷ expected then scales every later stage too, because the stages' relative costs come from the measured
 * desktop runs (rough step : detailed step : decode : texture). The step that is running counts as at least as long as
 * it has taken so far. The estimate leans long: a 10 % margin, and per-step times are averaged with the slowest of the
 * last three.
 *
 * Shown estimate ([displaySec], [text]): it counts down with the clock from an anchor and only re-anchors when the raw
 * estimate differs a lot. It goes up only if the raw estimate is more than 15 % (and 3 min) above it, and it drops
 * early only if the raw estimate is more than 12 % (and 3 min) below. So it never wobbles up by a minute or two.
 */
class Pixal3DEta(private val structureSteps: Int, private val shapeSteps: Int, private val prior: DeviceTimes, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Stage { PREPARE, STRUCTURE, SHAPE, DECODE, TEXTURE, DONE }
    private val sampling = setOf(Stage.STRUCTURE, Stage.SHAPE)
    private val observed = mutableMapOf<Stage, MutableList<Double>>()          // timed seconds per step, per sampling stage
    private val fixedMeasured = mutableMapOf<Stage, Double>()                  // measured seconds of finished fixed stages
    var stage = Stage.PREPARE; private set
    private var stageStart = clock(); private var stepStart = stageStart; private var stepsDone = 0
    /** Measured ÷ expected speed of the last stage with timed steps (1 = as expected; 2 = twice as slow). */
    var speedFactor = 1.0; private set

    // the step counts the pipeline reports ("Detailed shape 2/20" → 20) win over the counts this was built with
    private val reportedSteps = mutableMapOf<Stage, Int>()
    fun steps(s: Stage) = reportedSteps[s] ?: when (s) { Stage.STRUCTURE -> structureSteps; Stage.SHAPE -> shapeSteps; else -> 0 }
    private fun priorPerStep(s: Stage) = when (s) { Stage.STRUCTURE -> prior.structureStepSec; Stage.SHAPE -> prior.shapeStepSec; else -> null }
    private fun priorFixed(s: Stage) = when (s) { Stage.PREPARE -> prior.prepareSec; Stage.DECODE -> prior.decodeSec; Stage.TEXTURE -> prior.textureSec; else -> null }

    /** The run moved to [s]; a fixed stage it leaves records how long it took. */
    fun enter(s: Stage) {
        if (s == stage) return
        val now = clock()
        if (stage !in sampling && stage != Stage.DONE) fixedMeasured[stage] = (now - stageStart) / 1000.0
        stage = s; stageStart = now; stepStart = now; stepsDone = 0
    }

    /** Sampling step [done] (1-based) of the current stage finished. */
    fun step(done: Int) {
        val now = clock(); val n = (done - stepsDone).coerceAtLeast(1)
        if (stage in sampling) {
            val per = (now - stepStart) / 1000.0 / n
            if (per > 0) { observed.getOrPut(stage) { mutableListOf() }.add(per); measuredPerStep(stage)?.let { m -> priorPerStep(stage)?.let { p -> if (p > 0) speedFactor = m / p } } }
        }
        stepsDone = done; stepStart = now
    }

    /** Live seconds per step of [s]: the mean of its timed steps averaged with the slowest of the last three (leans long). */
    private fun measuredPerStep(s: Stage): Double? {
        val o = observed[s] ?: return null; if (o.isEmpty()) return null
        return (o.average() + o.takeLast(3).max()) / 2
    }

    /**
     * A detailed step is at least [SHAPE_VS_STRUCTURE_MIN] × this run's rough step until detailed steps are timed:
     * on the desktop box a detailed step costs a third of a rough one, but on the WP100 it was nearly as slow.
     */
    private fun shapeFloor(): Double? = measuredPerStep(Stage.STRUCTURE)?.let { it * SHAPE_VS_STRUCTURE_MIN }

    /** Seconds per step for [s]: measured (weighted by how many steps were timed) blended with the expected value. */
    private fun perStep(s: Stage): Double? {
        val expected = priorPerStep(s)?.let { it * speedFactor }?.let { e -> if (s == Stage.SHAPE) max(e, shapeFloor() ?: 0.0) else e }
        val m = measuredPerStep(s) ?: return expected
        val n = observed[s]!!.size; val w = when (n) { 1 -> 0.75; 2 -> 0.9; else -> 0.95 }
        return if (expected == null) m else w * m + (1 - w) * expected
    }

    /** Raw seconds left in the whole run (leans long), or null while nothing is known about this phone yet. */
    fun remainingSec(): Double? {
        if (stage == Stage.DONE) return 0.0
        val now = clock(); var left = 0.0
        val order = Stage.entries.filter { it != Stage.DONE }
        for (s in order.drop(order.indexOf(stage))) {
            left += if (s in sampling) {
                val per = perStep(s) ?: return null
                if (s != stage) per * steps(s) else {
                    // the running step counts as at least as long as it has taken so far (+10 %)
                    val inStep = (now - stepStart) / 1000.0
                    val thisStep = maxOf(per, inStep * 1.1) - inStep
                    thisStep + per * (steps(s) - stepsDone - 1).coerceAtLeast(0)
                }
            } else {
                val cost = (priorFixed(s) ?: return null) * speedFactor
                if (s != stage) cost else max(cost - (now - stageStart) / 1000.0, cost * 0.1)
            }
        }
        return left * MARGIN
    }

    // what is shown: counts down from an anchor; re-anchors only on a big difference
    private var anchor: Double? = null; private var anchorAt = 0L

    /** The time left as shown: monotonic between re-anchors, never nudged up by small amounts. */
    fun displaySec(): Double? {
        val raw = remainingSec() ?: return null
        val now = clock()
        if (stage == Stage.DONE) { anchor = 0.0; anchorAt = now; return 0.0 }
        val a = anchor
        if (a == null) { anchor = raw; anchorAt = now; return raw }
        val shown = max(0.0, a - (now - anchorAt) / 1000.0)
        val up = raw - shown; val down = shown - raw
        if (up > max(UP_REL * shown, UP_ABS_SEC) || (down > max(DOWN_REL * shown, DOWN_ABS_SEC))) { anchor = raw; anchorAt = now; return raw }
        // the countdown would reach zero while work is clearly left: hold at the raw estimate's floor instead of "<1 min"
        return if (shown < 60 && raw > 120) { anchor = raw; anchorAt = now; raw } else shown
    }

    fun text() = Eta.format(displaySec())

    /**
     * Feed [Pixal3D]'s progress callback: "Rough shape 3/8" / "Detailed shape 5/12" are sampling steps; otherwise the
     * stage follows the fraction (Pixal3D's stage boundaries: 0.10 structure, 0.50 shape, 0.86 decode, 0.94 texture, 1 done).
     */
    fun onProgress(what: String, f: Float) {
        val m = STEP.find(what)
        if (m != null) {
            val s = if (m.groupValues[1] == "Rough") Stage.STRUCTURE else Stage.SHAPE; val done = m.groupValues[2].toInt()
            m.groupValues[3].toIntOrNull()?.takeIf { it > 0 }?.let { reportedSteps[s] = it }
            // first report of a stage with no "stage started" message before it (e.g. after a resume): when its steps
            // began is unknown, so this one isn't timed (timing it from now would record a 0 s step)
            if (s != stage) { enter(s); stepsDone = done; stepStart = clock() } else step(done)
            return
        }
        enter(when { f >= 1f -> Stage.DONE; f >= 0.94f -> Stage.TEXTURE; f >= 0.86f -> Stage.DECODE; f >= 0.50f -> Stage.SHAPE; f >= 0.10f -> Stage.STRUCTURE; else -> Stage.PREPARE })
    }

    companion object {
        private val STEP = Regex("^(Rough|Detailed) shape (\\d+)/(\\d+)")
        const val MARGIN = 1.10
        /** WP100 (0.1.3, 12 + 20 steps): rough ≈ 3.5 min/step, detailed ≈ 3 min/step, so detailed ≈ 0.85 × rough. */
        const val SHAPE_VS_STRUCTURE_MIN = 0.8
        const val UP_REL = 0.15; const val UP_ABS_SEC = 180.0
        const val DOWN_REL = 0.12; const val DOWN_ABS_SEC = 180.0
    }

    /**
     * This run's measured times blended into the saved ones (half old, half new) for the next run. Saved after every
     * timed step too ([finished] = false), so an interrupted run still teaches the next estimate this phone's speed.
     * Stages not measured this run are scaled by [speedFactor] when the saved values were only a first guess.
     */
    fun learned(old: DeviceTimes, finished: Boolean = true): DeviceTimes {
        val guess = old.runs == 0 && !old.measuredMidRun
        fun mix(a: Double?, b: Double?): Double? = when {
            guess -> b ?: a?.times(speedFactor)           // a first guess is replaced, or scaled to the measured speed
            a == null -> b; b == null -> a
            else -> (a + b) / 2
        }
        val shape = mix(old.shapeStepSec, measuredPerStep(Stage.SHAPE))
            ?.let { v -> if (measuredPerStep(Stage.SHAPE) == null && guess) max(v, shapeFloor() ?: 0.0) else v }
        return DeviceTimes(old.device, mix(old.structureStepSec, measuredPerStep(Stage.STRUCTURE)), shape,
            mix(old.prepareSec, fixedMeasured[Stage.PREPARE]), mix(old.decodeSec, fixedMeasured[Stage.DECODE]), mix(old.textureSec, fixedMeasured[Stage.TEXTURE]),
            old.runs + if (finished) 1 else 0, measuredMidRun = true)
    }
}

/** Seconds per sampling step and per fixed stage on one device; null = not measured yet. */
data class DeviceTimes(val device: String, val structureStepSec: Double?, val shapeStepSec: Double?,
                       val prepareSec: Double?, val decodeSec: Double?, val textureSec: Double?, val runs: Int = 0,
                       /** true once any step was timed on this phone (also from a run that didn't finish) */
                       val measuredMidRun: Boolean = false) {
    fun json(): String = JSONObject().put("device", device).put("runs", runs).put("measured", measuredMidRun).apply {
        structureStepSec?.let { put("structure_step_sec", it) }; shapeStepSec?.let { put("shape_step_sec", it) }
        prepareSec?.let { put("prepare_sec", it) }; decodeSec?.let { put("decode_sec", it) }; textureSec?.let { put("texture_sec", it) }
    }.toString(1)

    companion object {
        /**
         * Saved times for [device] (a backup restored on another phone is ignored), else a first guess:
         * [secPerCallStructure]/[secPerCallShape] × model calls per step (from the run plan) and fixed stages scaled the same way.
         */
        fun load(json: String?, device: String, guessStructureStep: Double, guessShapeStep: Double): DeviceTimes {
            val o = runCatching { JSONObject(json!!) }.getOrNull()
            fun d(k: String) = o?.optDouble(k)?.takeIf { !it.isNaN() && it > 0 }
            if (o != null && o.optString("device") == device)
                return DeviceTimes(device, d("structure_step_sec"), d("shape_step_sec"), d("prepare_sec"), d("decode_sec"), d("texture_sec"), o.optInt("runs"),
                    o.optBoolean("measured", o.optInt("runs") > 0))
            // desktop reference run (shape ≈ 6.6 s/call): prepare ~25 s, decode ~90 s, texture + simplify ~40 s; scale by the step guess
            val k = guessShapeStep / (6.6 * 2)
            return DeviceTimes(device, guessStructureStep, guessShapeStep, 25.0 * k, 90.0 * k, 40.0 * k, 0)
        }
    }
}

/**
 * Downloads: bytes left / smoothed speed. Speed is sampled at most once a second over all running downloads together
 * (they share the connection), and the last speed is kept so a resumed queue starts with a sensible guess.
 * Queued files count as "in line": their ETA includes everything ahead of them.
 */
class TransferEta(startBytesPerSec: Double? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val speed = Eta.Smooth(0.2, startBytesPerSec)
    private var lastT = 0L; private var lastBytes = -1L
    val bytesPerSec get() = speed.value

    /** [totalDone] = bytes downloaded so far by everything in the queue. */
    fun sample(totalDone: Long) {
        val now = clock()
        if (lastBytes < 0 || totalDone < lastBytes) { lastT = now; lastBytes = totalDone; return }
        if (now - lastT < 1000) return
        speed.add((totalDone - lastBytes) * 1000.0 / (now - lastT)); lastT = now; lastBytes = totalDone
    }

    fun remainingSec(bytesLeft: Long): Double? = speed.value?.takeIf { it > 1.0 }?.let { bytesLeft / it }

    /** ETA for each item in queue order ([left] = its bytes left): items further back wait for those ahead. */
    fun queue(left: List<Long>): List<Double?> { var ahead = 0L; return left.map { ahead += it; remainingSec(ahead) } }

    fun text(bytesLeft: Long) = Eta.format(remainingSec(bytesLeft))
}
