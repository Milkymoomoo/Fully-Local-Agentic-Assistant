package app.belle.assistant.threed

import app.belle.assistant.core.Stage3D
import app.belle.assistant.core.Workflow3DOverrides
import app.belle.assistant.core.Workflow3DSettings
import kotlin.math.roundToInt

/**
 * What a photo → 3D run on this phone will actually use, made from the imported ComfyUI workflow (or the phone
 * defaults), fitted to the device, with the user's per-value overrides on top. Pure: unit tested.
 *
 * Fitting (only values you haven't overridden):
 *  - steps: the two sampling stages are scaled down together until the estimated sampling time fits [Device.budgetMin];
 *    per-call times come from this phone's last run (last_run.json) or, before any run, a desktop estimate;
 *  - crop size: at most 1024 px (the largest input the phone pipeline uses), 768 px on phones under 8 GB;
 *  - triangles: at most 150k (250k on phones with 12 GB or more), what the avatar renderer handles comfortably;
 *  - sampler / scheduler: euler with "normal" or "simple"; anything else runs as euler / normal.
 * Overrides win, kept only inside hard safety ranges (steps 1–60, cfg 0–15, crop 512–1024, triangles 20k–300k).
 */
object Run3DPlan {
    /** [accel]: what runs the models ("cpu" on the phone; the Windows program's backend, e.g. "cuda"), shown in the notes. */
    data class Device(val ramGb: Double, val secPerCallStructure: Double, val secPerCallShape: Double, val measured: Boolean, val budgetMin: Double = 30.0,
                      val accel: String = "cpu", val accelLabel: String = "the CPU") {
        companion object {
            /** Desktop CPU measurement (4 threads, tools/pixal3d_port/proof/e2e_phone_set_final.json): ss flow 278.6 s / 14 calls,
             *  shape flow 138.3 s / 21 calls at N≈1515. Used until this phone has done a run of its own. */
            const val DESKTOP_SS = 19.9; const val DESKTOP_SHAPE = 6.6
            fun estimate(ramGb: Double) = Device(ramGb, DESKTOP_SS, DESKTOP_SHAPE, measured = false)
            /** A GPU backend before its first run: the CPU times divided by a deliberately low [speedup], so the time
             *  left is never promised too short and the steps are not cut more than the CPU would need. */
            fun estimate(ramGb: Double, accel: String, accelLabel: String, speedup: Double) =
                Device(ramGb, DESKTOP_SS / speedup.coerceAtLeast(1.0), DESKTOP_SHAPE / speedup.coerceAtLeast(1.0), measured = false, accel = accel, accelLabel = accelLabel)
        }
    }

    data class StageRun(val name: String, val steps: Int, val cfg: Double, val seed: Long, val randomSeed: Boolean,
                        val sampler: String, val scheduler: String, val shift: Double, val rescale: Double, val overrideStart: Double) {
        fun params() = FlowSampler.Params(steps, shift, cfg, rescale, overrideStart, baseShift = 3.0, scheduler = scheduler)
        /** The seed for this run: the fixed one, or [random] when the workflow says "randomize". */
        fun seedFor(random: () -> Long) = if (randomSeed) random() else seed
    }

    /** One row of the "what the phone uses" table. [overridable] = the UI offers an edit for it. */
    data class Item(val stage: String?, val key: String, val label: String, val workflow: String, val used: String,
                    val note: String?, val overridden: Boolean, val adjusted: Boolean, val overridable: Boolean = true)

    data class Plan(val structure: StageRun, val shape: StageRun, val removeBackground: Boolean, val cropSize: Int, val cropPadding: Double,
                    val maxTriangles: Int, val estMinutes: Double, val items: List<Item>, val notes: List<String>)

    const val MAX_STEPS = 60; const val MIN_FIT_STEPS = 4

    fun plan(wf: Workflow3DSettings, ov: Map<String, Any?>, dev: Device): Plan {
        val items = ArrayList<Item>(); val notes = ArrayList<String>(wf.notes)
        val eff = wf.withOverrides(Workflow3DOverrides.write(ov))
        fun ovd(stage: String?, key: String) = Workflow3DOverrides.has(ov, stage, key)

        fun stageRun(name: String, defShift: Double, defRescale: Double, defOverride: Double): StageRun {
            val s0: Stage3D = wf.stage(name) ?: Workflow3DSettings.PHONE_DEFAULT.stage(name)!!
            val s = eff.stage(name) ?: s0
            val sampler = if (s.sampler == "euler") "euler" else "euler".also { notes += "$name: sampler \"${s.sampler}\" isn't available on the phone; euler is used." }
            val sched = if (s.scheduler in FlowSampler.SCHEDULERS) s.scheduler else "normal".also { notes += "$name: scheduler \"${s.scheduler}\" isn't available on the phone; normal is used." }
            if (s.denoise != 1.0) notes += "$name: denoise ${s.denoise} is ignored (the phone always samples from noise)."
            val cfg = if (ovd(name, "cfg")) s.cfg.coerceIn(0.0, 15.0) else s.cfg
            return StageRun(name, s.steps, cfg, s.seed, s.randomSeed, sampler, sched, s.shift ?: defShift, s.rescale ?: defRescale, s.overrideStart ?: defOverride)
        }
        var st = stageRun("Structure", 3.0, 0.0, 1.0)
        var sh = stageRun("Shape", 3.0, 0.0, 1.0)
        fun est(a: StageRun, b: StageRun) = (FlowSampler.modelCalls(a.params()) * dev.secPerCallStructure + FlowSampler.modelCalls(b.params()) * dev.secPerCallShape) / 60.0

        // steps: overrides kept (1..60); the rest scaled together to the time budget
        val wfSt = st.steps; val wfSh = sh.steps
        val ovSt = ovd("Structure", "steps"); val ovSh = ovd("Shape", "steps")
        st = st.copy(steps = st.steps.coerceIn(1, MAX_STEPS)); sh = sh.copy(steps = sh.steps.coerceIn(1, MAX_STEPS))
        val before = est(st, sh)
        if (before > dev.budgetMin && (!ovSt || !ovSh)) {
            var k = 1.0
            while (k > 0.05) {
                k -= 0.05
                val a = if (ovSt) st else st.copy(steps = maxOf(MIN_FIT_STEPS, (wfSt.coerceAtMost(MAX_STEPS) * k).roundToInt()))
                val b = if (ovSh) sh else sh.copy(steps = maxOf(MIN_FIT_STEPS, (wfSh.coerceAtMost(MAX_STEPS) * k).roundToInt()))
                if (est(a, b) <= dev.budgetMin || (a.steps == MIN_FIT_STEPS || ovSt) && (b.steps == MIN_FIT_STEPS || ovSh)) { st = a; sh = b; break }
            }
        }
        val timeWhy = "fits a ~${dev.budgetMin.roundToInt()} min sampling budget (${"%.0f".format(before)} min with the workflow's steps; " +
            (if (dev.measured) "timed from this ${app.belle.assistant.core.Platform.device}'s last run" + (if (dev.accel != "cpu") " on ${dev.accelLabel})" else ")")
             else if (dev.accel != "cpu") "a cautious estimate for ${dev.accelLabel} until this ${app.belle.assistant.core.Platform.device} has done a run on it)"
             else "estimated from a desktop run until this ${app.belle.assistant.core.Platform.device} has done one)")
        fun stepNote(wfv: Int, used: Int, o: Boolean) = when { o -> "your value"; used != wfv && wfv > MAX_STEPS && used == MAX_STEPS -> "capped at $MAX_STEPS steps"; used != wfv -> timeWhy; else -> null }
        for ((r, wfv, o) in listOf(Triple(st, wfSt, ovSt), Triple(sh, wfSh, ovSh))) {
            val w0 = wf.stage(r.name)
            items += Item(r.name, "steps", "${r.name}: steps", (w0?.steps ?: wfv).toString(), r.steps.toString(), stepNote(w0?.steps ?: wfv, r.steps, o), o, !o && r.steps != (w0?.steps ?: wfv))
            items += Item(r.name, "cfg", "${r.name}: guidance (cfg)", "%.1f".format(w0?.cfg ?: r.cfg), "%.1f".format(r.cfg), if (ovd(r.name, "cfg")) "your value" else null, ovd(r.name, "cfg"), false)
            items += Item(r.name, "seed", "${r.name}: seed", if (w0?.randomSeed == true) "random" else (w0?.seed ?: r.seed).toString(),
                if (r.randomSeed) "random each run" else r.seed.toString(), if (ovd(r.name, "seed") || ovd(r.name, "random")) "your value" else null, ovd(r.name, "seed") || ovd(r.name, "random"), false)
            items += Item(r.name, "scheduler", "${r.name}: sampler / scheduler", "${w0?.sampler ?: r.sampler} / ${w0?.scheduler ?: r.scheduler}", "${r.sampler} / ${r.scheduler}",
                if (ovd(r.name, "scheduler")) "your value" else if (w0 != null && (w0.sampler != r.sampler || w0.scheduler != r.scheduler)) "not available on the ${app.belle.assistant.core.Platform.device}" else null,
                ovd(r.name, "scheduler"), w0 != null && (w0.sampler != r.sampler || w0.scheduler != r.scheduler) && !ovd(r.name, "scheduler"))
            items += Item(r.name, "patches", "${r.name}: shift · rescale · cfg 1 from", "", "${r.shift} · ${r.rescale} · ${(r.overrideStart * 100).roundToInt()} %", null, false, false, overridable = false)
        }

        // crop size
        val capCrop = if (dev.ramGb < 8.0) 768 else 1024
        val crop = if (ovd(null, "cropSize")) eff.cropSize.coerceIn(512, 1024) else eff.cropSize.coerceIn(512, capCrop)
        items += Item(null, "cropSize", "Picture size (crop)", "${wf.cropSize} px", "$crop px",
            when { ovd(null, "cropSize") -> "your value"; crop < wf.cropSize && capCrop < 1024 -> "${if (app.belle.assistant.core.Platform.desktop) "PCs" else "phones"} under 8 GB use 768 px to save memory"
                crop < wf.cropSize -> "the pipeline's largest input is 1024 px; bigger only costs memory"; crop > wf.cropSize -> "at least 512 px"; else -> null },
            ovd(null, "cropSize"), !ovd(null, "cropSize") && crop != wf.cropSize)
        // triangles
        val capTri = if (dev.ramGb >= 12.0) 250_000 else 150_000
        val tri = if (ovd(null, "decimateTarget")) eff.decimateTarget.coerceIn(20_000, 300_000) else eff.decimateTarget.coerceIn(20_000, capTri)
        items += Item(null, "decimateTarget", "Triangles (decimate)", "%,d".format(wf.decimateTarget), "%,d".format(tri),
            when { ovd(null, "decimateTarget") -> "your value"; tri < wf.decimateTarget -> "the ${app.belle.assistant.core.Platform.device} draws up to %,d comfortably (%.0f GB RAM)".format(capTri, dev.ramGb); else -> null },
            ovd(null, "decimateTarget"), !ovd(null, "decimateTarget") && tri != wf.decimateTarget)
        items += Item(null, "removeBackground", "Background", if (wf.removeBackground) "remove (BiRefNet)" else "picture's own transparency",
            if (eff.removeBackground) "remove (BiRefNet)" else "picture's own transparency (falls back to removal if it has none)",
            if (ovd(null, "removeBackground")) "your value" else null, ovd(null, "removeBackground"), false)
        // workflow values the phone pipeline has no stage for (shown, not editable)
        val skip = "not run here (no model for it in the 3D set)"
        items += Item(null, "upsampleRes", "Shape detail (refine)", "${wf.stage("Shape detail")?.steps ?: 0} steps at ${wf.upsampleRes}", "skipped", skip, false, false, false)
        items += Item(null, "textureSize", "Texture stage / size", "${wf.textureSize} px", "colours painted from the photo onto the vertices", skip, false, false, false)
        items += Item(null, "remesh", "Remesh · UV unwrap · normal / AO maps", "${wf.remeshRes} · ${wf.unwrapRes} · ${wf.normalMapSize} / ${wf.aoSize}", "skipped", skip, false, false, false)
        return Plan(st, sh, eff.removeBackground, crop, wf.cropPadding.coerceIn(1.0, 1.5), tri, est(st, sh), items, notes.distinct())
    }

    /** Per-call seconds measured by this device's last run (last_run.json) on the same [accel] backend, or null. */
    fun measured(lastRunJson: String?, accel: String = "cpu"): Pair<Double, Double>? = runCatching {
        val o = org.json.JSONObject(lastRunJson ?: return null)
        if (o.optString("accel", "cpu") != accel) return null   // a CPU run says nothing about the GPU's speed, and back
        val a = o.getDouble("ss_ms") / 1000 / o.getInt("ss_calls"); val b = o.getDouble("shape_ms") / 1000 / o.getInt("shape_calls")
        if (a > 0 && b > 0) a to b else null
    }.getOrNull()

    /** The parts of a plan a run needs, as JSON, so a resumed run uses exactly the same settings (and the same seeds). */
    fun toJson(p: Plan): String {
        fun st(r: StageRun) = org.json.JSONObject().put("name", r.name).put("steps", r.steps).put("cfg", r.cfg).put("seed", r.seed).put("random", r.randomSeed)
            .put("sampler", r.sampler).put("scheduler", r.scheduler).put("shift", r.shift).put("rescale", r.rescale).put("override", r.overrideStart)
        return org.json.JSONObject().put("structure", st(p.structure)).put("shape", st(p.shape)).put("removeBackground", p.removeBackground)
            .put("cropSize", p.cropSize).put("cropPadding", p.cropPadding).put("maxTriangles", p.maxTriangles).put("estMinutes", p.estMinutes).toString()
    }
    fun fromJson(json: String): Plan {
        val o = org.json.JSONObject(json)
        fun st(j: org.json.JSONObject) = StageRun(j.getString("name"), j.getInt("steps"), j.getDouble("cfg"), j.getLong("seed"), j.getBoolean("random"),
            j.getString("sampler"), j.getString("scheduler"), j.getDouble("shift"), j.getDouble("rescale"), j.getDouble("override"))
        return Plan(st(o.getJSONObject("structure")), st(o.getJSONObject("shape")), o.getBoolean("removeBackground"), o.getInt("cropSize"),
            o.getDouble("cropPadding"), o.getInt("maxTriangles"), o.getDouble("estMinutes"), emptyList(), emptyList())
    }
    /** Random seeds drawn now and written into the plan, so a resumed run continues the same model rather than a new one. */
    fun withFixedSeeds(p: Plan, random: () -> Long): Plan = p.copy(
        structure = if (p.structure.randomSeed) p.structure.copy(seed = random(), randomSeed = false) else p.structure,
        shape = if (p.shape.randomSeed) p.shape.copy(seed = random(), randomSeed = false) else p.shape)
}
