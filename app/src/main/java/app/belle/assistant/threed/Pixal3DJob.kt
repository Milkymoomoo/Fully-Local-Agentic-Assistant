package app.belle.assistant.threed

import android.content.Context
import android.graphics.Bitmap
import app.belle.assistant.avatar.Avatar3DFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Photo → 3D avatar on this phone: [Pixal3D.generate], then the normal avatar import (detail levels, rig, mouth). */
object Pixal3DJob {
    /** Files the on-phone run needs (int8 variants where they exist). */
    val REQUIRED = listOf("pixal3d_ss_flow.onnx", "trellis2_struct_dec.onnx", "pixal3d_shape512_flow.onnx", "naf_q_r1.onnx",
        "trellis2_shape_dec_seg0.onnx", "trellis2_shape_dec_seg1.onnx", "trellis2_shape_dec_seg2.onnx", "trellis2_shape_dec_seg3.onnx", "trellis2_shape_dec_seg4.onnx")
    fun dir(ctx: Context) = Port3D.dir(ctx)
    fun missing(ctx: Context): List<String> {
        val d = dir(ctx)
        val m = REQUIRED.filterNot { File(d, it).exists() }.toMutableList()
        if (!File(d, "dinov3_l_nb8.onnx").exists() && !File(d, "dinov3_l.onnx").exists()) m += "dinov3_l_nb8.onnx"
        if (!File(d, "moge2_l_3600_nb8.onnx").exists() && !File(d, "moge2_l_3600.onnx").exists()) m += "moge2_l_3600_nb8.onnx"
        return m
    }
    fun available(ctx: Context) = missing(ctx).isEmpty()

    /**
     * Side-loading the generator: the user copies the model folder (made by tools/pixal3d_port/make_bundle.py: the
     * .onnx files, their .onnx.data weights and manifest.json with each file's size and sha256) to the phone and picks it.
     * Every manifest file is copied into models/3d with its sha256 checked while copying; a bad or short file is deleted.
     * Returns a summary. [progress](file, done bytes, total bytes).
     */
    fun importModels(ctx: Context, tree: android.net.Uri, progress: (String, Long, Long) -> Unit = { _, _, _ -> }): String {
        val cr = ctx.contentResolver
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val kids = HashMap<String, android.net.Uri>()
        val q = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getDocumentId(root))
        cr.query(q, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) kids[c.getString(1)] = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) }
        val man = JSONObject(cr.openInputStream(kids["manifest.json"] ?: error("That folder has no manifest.json (it isn't a Pixal3D model folder)"))!!.use { it.readBytes().decodeToString() })
        val files = man.getJSONArray("files"); val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("size") }
        val d = dir(ctx); var done = 0L
        for (i in 0 until files.length()) {
            val e = files.getJSONObject(i); val name = e.getString("name"); val size = e.getLong("size"); val sha = e.getString("sha256")
            require(!name.contains('/') && !name.startsWith(".")) { "bad file name $name" }
            val out = File(d, name)
            if (out.length() == size && File(d, "$name.sha256").takeIf { it.exists() }?.readText() == sha) { done += size; continue }   // already there
            val src = kids[name] ?: error("$name is missing from the folder")
            val tmp = File(d, "$name.part"); val md = java.security.MessageDigest.getInstance("SHA-256"); var n = 0L
            cr.openInputStream(src)!!.use { inp -> tmp.outputStream().use { o -> val buf = ByteArray(1 shl 20)
                while (true) { val r = inp.read(buf); if (r < 0) break; o.write(buf, 0, r); md.update(buf, 0, r); n += r; done += r
                    if ((n and ((64L shl 20) - 1)) < r) progress(name, done, total) } } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (n != size || got != sha) { tmp.delete(); error("$name didn't copy correctly (size $n of $size, checksum ${if (got == sha) "ok" else "wrong"})") }
            out.delete(); check(tmp.renameTo(out)) { "couldn't save $name" }; File(d, "$name.sha256").writeText(sha)
        }
        File(d, "manifest.json").writeText(man.toString())
        runCatching { Pixal3DInstall.get(ctx).refresh() }
        return "3D generator models ready (${"%.1f".format(total / 1e9)} GB, version ${man.optString("version")})"
    }
    fun removeModels(ctx: Context) { dir(ctx).listFiles()?.forEach { it.deleteRecursively() }; Pixal3DInstall.get(ctx).refresh() }
    fun modelBytes(ctx: Context) = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    @Volatile var running = false; private set
    @Volatile private var cancel = false
    fun cancel() { cancel = true }

    fun lastRun(ctx: Context) = File(dir(ctx), "last_run.json")
    /** "about 12 min left" for the run in progress (null when nothing runs). */
    @Volatile private var eta: app.belle.assistant.core.Pixal3DEta? = null
    val etaText: String? get() = eta?.text()   // recomputed on every read, so it counts down between steps too
    /** Measured seconds per step / per stage on this phone, kept outside the model folder so removing models keeps them. */
    fun timesFile(ctx: Context) = File(ctx.filesDir, "pixal3d_times.json")
    fun deviceKey() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    fun deviceTimes(ctx: Context, plan: Run3DPlan.Plan = plan(ctx)): app.belle.assistant.core.DeviceTimes {
        val dev = device(ctx)
        // never measured on this phone: the per-call times are the desktop's, so scale them to a phone (leans long)
        val k = if (dev.measured) 1.0 else app.belle.assistant.core.Eta.PHONE_VS_DESKTOP
        fun perStep(p: FlowSampler.Params, secPerCall: Double) = k * secPerCall * FlowSampler.modelCalls(p) / p.steps.coerceAtLeast(1)
        return app.belle.assistant.core.DeviceTimes.load(runCatching { timesFile(ctx).readText() }.getOrNull(), deviceKey(),
            perStep(plan.structure.params(), dev.secPerCallStructure), perStep(plan.shape.params(), dev.secPerCallShape))
    }
    fun ramGb(ctx: Context): Double { val mi = android.app.ActivityManager.MemoryInfo()
        ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); return mi.totalMem / 1073741824.0 }
    /** This phone: its RAM and, after a first run, its own per-call sampling times. */
    fun device(ctx: Context): Run3DPlan.Device {
        val a = accel(); val ram = ramGb(ctx); val m = Run3DPlan.measured(runCatching { lastRun(ctx).readText() }.getOrNull(), a.id)
        return when {
            m != null -> Run3DPlan.Device(ram, m.first, m.second, measured = true, accel = a.id, accelLabel = a.label)
            a.id == "cpu" -> Run3DPlan.Device.estimate(ram)
            else -> Run3DPlan.Device.estimate(ram, a.id, a.label, a.speedup)
        }
    }
    /** What runs the models: always the CPU on the phone; the Windows program sets its backend at startup, with a
     *  cautious first-run speed-up over the CPU (replaced by real timings after one run on it). */
    class Accel(val id: String, val label: String, val speedup: Double)
    @Volatile var accel: () -> Accel = { Accel("cpu", "the CPU", 1.0) }
    /** What the next run will use: the imported workflow (or the phone defaults) fitted to this phone, plus your overrides. */
    fun plan(ctx: Context): Run3DPlan.Plan = Run3DPlan.plan(Avatar3DFiles.baseSettings(ctx), Avatar3DFiles.overridesMap(ctx), device(ctx))

    // ---- resumable runs: a job record + checkpoints in files/pixal3d_job/ (see Pixal3DCheckpoint) ----
    fun jobDir(ctx: Context) = File(ctx.filesDir, "pixal3d_job")
    private fun jobFile(ctx: Context) = File(jobDir(ctx), "job.json")
    /** The unfinished run (profile, plan with fixed seeds, attempts), or null. A damaged record counts as none. */
    fun pendingJob(ctx: Context): JSONObject? {
        val j = app.belle.assistant.core.AtomicFiles.readSealed(jobFile(ctx))?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return null
        // a job made by an older version (0.1.3 jobs have no app_version): the update may fix what failed, so the tries
        // start again and the saved progress (same checkpoint format since 0.1.3) is resumed, not redone
        if (!running) app.belle.assistant.core.BuildJobRules.afterUpdate(j, versionCode(), versionName())?.let { msg ->
            saveJob(ctx, j); log(ctx, "p3d: $msg (saved progress: ${runCatching { describe(ctx, j) }.getOrDefault("?")})") }
        return j
    }
    fun versionCode() = app.belle.assistant.BuildConfig.VERSION_CODE
    fun versionName() = app.belle.assistant.BuildConfig.VERSION_NAME
    private fun describe(ctx: Context, j: JSONObject): String {
        val plan = runCatching { Run3DPlan.fromJson(j.getString("plan")) }.getOrNull() ?: return "the start"
        return Pixal3DCheckpoint(File(jobDir(ctx), "ck")).describe(plan.structure.steps, plan.shape.steps)
    }
    /** Why the unfinished build won't resume by itself (null = it can, or there is none). */
    fun resumeBlocked(ctx: Context): String? = pendingJob(ctx)?.let { app.belle.assistant.core.BuildJobRules.blocked(it, versionCode(), MAX_ATTEMPTS) }
    private fun saveJob(ctx: Context, j: JSONObject) = app.belle.assistant.core.AtomicFiles.writeSealed(jobFile(ctx), j.toString().toByteArray())
    fun discardJob(ctx: Context) { jobDir(ctx).deleteRecursively() }
    private fun heapMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
    /** Crashed three times in a row at the same place: stop resuming by itself (the user can start again). */
    const val MAX_ATTEMPTS = 3
    @Volatile var lastResult: String? = null; private set
    @Volatile var lastError: String? = null; private set

    // ---- build history, the build's own event log, and the newest generated model ----
    fun history(ctx: Context) = app.belle.assistant.core.BuildLog(File(ctx.filesDir, "pixal3d_history.ck"))
    /** A note in the build log from outside the job (e.g. the Windows program's GPU backend falling back to the CPU). */
    fun note(ctx: Context, msg: String) = log(ctx, msg)
    private fun log(ctx: Context, msg: String) {
        app.belle.assistant.service.AssistantRuntime.event(msg); history(ctx).event(System.currentTimeMillis(), msg)
    }
    fun lastGlb(ctx: Context) = File(dir(ctx), "last_generated.glb")
    private fun attachedFile(ctx: Context) = File(dir(ctx), "last_generated.attached")
    @Volatile private var shaCache: Triple<Long, Long, String>? = null
    private fun lastGlbSha(ctx: Context): String? {
        val f = lastGlb(ctx); if (!f.isFile || f.length() == 0L) return null
        shaCache?.let { (len, mod, sha) -> if (len == f.length() && mod == f.lastModified()) return sha }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }.also { shaCache = Triple(f.length(), f.lastModified(), it) }
    }
    private fun markAttached(ctx: Context, profileId: String) { lastGlbSha(ctx)?.let {
        app.belle.assistant.core.AtomicFiles.write(attachedFile(ctx), app.belle.assistant.core.OrphanModel.attachedJson(it, profileId, System.currentTimeMillis()).toByteArray()) } }
    /** A generated model that no assistant got (an older version lost it, or its assistant was deleted). Not while a build runs. */
    fun orphan(ctx: Context): Boolean = !running && pendingJob(ctx) == null && runCatching {
        app.belle.assistant.core.OrphanModel.isOrphan(lastGlbSha(ctx), attachedFile(ctx).takeIf { it.isFile }?.readText()) }.getOrDefault(false)
    /** "Attach last generated model to…": gives last_generated.glb to [profileId] (atomic import) and turns its 3D on. */
    fun attachOrphan(ctx: Context, profileId: String): String {
        check(profileId in app.belle.assistant.ProfileStore.ids(ctx)) { "that assistant no longer exists" }
        val r = Avatar3DFiles.import(ctx, profileId, mapOf("pixal3d.glb" to lastGlb(ctx).readBytes()))
        app.belle.assistant.Prefs(ctx, profileId).show3d = true; markAttached(ctx, profileId)
        log(ctx, "p3d: attached last generated model to ${app.belle.assistant.ProfileStore.name(ctx, profileId)}"); return r
    }
    /** "Milly · rough shape step 5/8 · try 2 of 3" for the unfinished run, or null. */
    fun pendingSummary(ctx: Context): String? {
        val j = pendingJob(ctx) ?: return null
        val at = describe(ctx, j)
        val name = j.optString("name").ifEmpty { app.belle.assistant.ProfileStore.name(ctx, j.getString("profile")) }
        val f = j.optJSONObject("failure")?.let { " · last try: ${it.optString("message")} (at ${it.optString("stage")})" } ?: ""
        return "for $name · saved progress: $at · tries used ${j.optInt("attempts")} of $MAX_ATTEMPTS$f"
    }
    fun pendingProfile(ctx: Context): String? = pendingJob(ctx)?.optString("profile")

    /**
     * Start a new run for [profileId] (replacing any unfinished one). Blocking. Returns a one-line summary.
     * The target assistant is chosen by the caller and saved in the job record: the model can only ever go to it.
     */
    fun run(ctx: Context, profileId: String, photo: Bitmap, steps: Int = 0, import: Boolean = true): String {
        check(!running) { "a 3D model is already being made" }
        check(profileId in app.belle.assistant.ProfileStore.ids(ctx)) { "that assistant no longer exists" }
        val pending = pendingJob(ctx)
        if (pending != null) pending.optLong("build_id").takeIf { it > 0 }?.let { history(ctx).finish(it, app.belle.assistant.core.BuildRecord.CANCELLED, System.currentTimeMillis(), "replaced by a new build") }
        discardJob(ctx); jobDir(ctx).mkdirs()
        val png = java.io.ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        app.belle.assistant.core.AtomicFiles.writeSealed(File(jobDir(ctx), "photo.ck"), png)
        val plan = Run3DPlan.withFixedSeeds(plan(ctx)) { java.util.Random().nextInt(Int.MAX_VALUE).toLong() }
        val now = System.currentTimeMillis(); val name = app.belle.assistant.ProfileStore.name(ctx, profileId)
        history(ctx).start(now, profileId, name, now)
        saveJob(ctx, JSONObject().put("profile", profileId).put("name", name).put("steps", steps).put("import", import).put("plan", Run3DPlan.toJson(plan))
            .put("attempts", 0).put("created", now).put("build_id", now).put("app_version", versionCode()))
        log(ctx, "p3d: build started for $name")
        return execute(ctx, photo)
    }

    /** Continue an unfinished run after a kill, a dead battery or a reboot. Returns null when there is nothing to resume. */
    fun resume(ctx: Context): String? {
        if (running) return null
        val j = pendingJob(ctx) ?: run { if (jobDir(ctx).exists()) discardJob(ctx); return null }
        app.belle.assistant.core.BuildJobRules.blocked(j, versionCode(), MAX_ATTEMPTS)?.let { log(ctx, "p3d: not resuming: $it"); return null }
        // execute logs its own failures; this catches what happens before its own handler (and logs it: never silent)
        return runCatching { execute(ctx, null) }.getOrElse { e -> lastError = e.message
            if (e !is Pixal3D.Cancelled && !loggedFailure && e.message?.startsWith("a 3D model is already") != true) log(ctx, "p3d: the resumed build failed: ${app.belle.assistant.core.BuildJobRules.classify(e).message} (${e.javaClass.simpleName})"); null }
    }
    fun canResume(ctx: Context) = !running && pendingJob(ctx)?.let { app.belle.assistant.core.BuildJobRules.blocked(it, versionCode(), MAX_ATTEMPTS) == null } == true
    @Volatile private var loggedFailure = false
    /** Heap gate only ([HeapGate]): the process kills itself when this stage starts (a real death, to test resuming). */
    @Volatile var killAt: String? = null

    /**
     * Why this app's process last ended, from Android (API 30+), if it ended after [since]: "it crashed (native code)",
     * "Android ended it for low memory", ... Null when unknown.
     */
    fun lastExit(ctx: Context, since: Long): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val e = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).firstOrNull { it.timestamp >= since } ?: return null
        app.belle.assistant.core.BuildJobRules.exitReason(e.reason, e.description, e.status) + " at " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
    }.getOrNull()

    /** Marks a run that was cut off as interrupted, with Android's reason when it gives one (for the history and the UI). */
    fun settleHistory(ctx: Context) {
        val h = history(ctx); if (running || h.all().none { it.result == app.belle.assistant.core.BuildRecord.RUNNING }) return
        val since = h.all().lastOrNull { it.result == app.belle.assistant.core.BuildRecord.RUNNING }?.startedAt ?: 0L
        h.settle(running, System.currentTimeMillis(), lastExit(ctx, since))
    }

    /** Blocking: the run described by the job record, from its newest valid checkpoint. */
    private fun execute(ctx: Context, photoIn: Bitmap?): String {
        synchronized(this) { check(!running) { "a 3D model is already being made" }; running = true }
        cancel = false; lastResult = null; lastError = null
        val job = pendingJob(ctx) ?: run { running = false; error("no 3D job") }
        val attempt = job.optInt("attempts") + 1
        loggedFailure = false
        // the previous try never reached its own success / failure handler: the process was killed or crashed
        val openTry = job.optJSONObject("open_try")
        if (openTry != null) {
            val why = lastExit(ctx, openTry.optLong("since")) ?: "the app was closed, killed or crashed (Android gave no reason)"
            val f = app.belle.assistant.core.BuildJobRules.Failure(app.belle.assistant.core.BuildJobRules.NO_RESULT, "try ${openTry.optInt("try")} ended without a result: $why")
            app.belle.assistant.core.BuildJobRules.recordFailure(job, f, openTry.optString("stage", "?"), job.optInt("app_version", versionCode()))
            runCatching { log(ctx, "p3d: ${f.message} (at ${openTry.optString("stage", "?")})") }
        }
        job.put("open_try", JSONObject().put("try", attempt).put("since", System.currentTimeMillis()).put("stage", "starting"))
        saveJob(ctx, job.put("attempts", attempt).put("app_version", maxOf(job.optInt("app_version"), versionCode())))   // counted before the work, so a crash loop ends
        val profileId = job.getString("profile"); val steps = job.optInt("steps"); val import = job.optBoolean("import", true)
        val name = job.optString("name").ifEmpty { app.belle.assistant.ProfileStore.name(ctx, profileId) }
        val hist = history(ctx)
        val buildId = job.optLong("build_id").takeIf { it > 0 } ?: System.currentTimeMillis().also { hist.start(it, profileId, name, it); saveJob(ctx, job.put("build_id", it)) }
        if (attempt > 1) { hist.resumed(buildId); log(ctx, "p3d: resuming the build for $name (try $attempt of $MAX_ATTEMPTS)") }
        var peak = 0; var stage = "starting"
        /** The model goes ONLY to the assistant chosen at the start; if it was deleted meanwhile, the model is kept as an orphan. */
        fun importTo(glb: ByteArray): String {
            if (!import) return "not imported"
            check(profileId in app.belle.assistant.ProfileStore.ids(ctx)) { "$name was deleted during the build; the model is kept: use Attach last generated model" }
            return Avatar3DFiles.import(ctx, profileId, mapOf("pixal3d.glb" to glb)).also { app.belle.assistant.Prefs(ctx, profileId).show3d = true; markAttached(ctx, profileId) }
        }
        val ck = Pixal3DCheckpoint(File(jobDir(ctx), "ck"))
        try {
            app.belle.assistant.avatar.Cutout.init(ctx)
            val p3 = Pixal3D(dir(ctx)); val plan = Run3DPlan.fromJson(job.getString("plan"))
            app.belle.assistant.service.AssistantRuntime.event("p3d plan: structure ${plan.structure.steps} steps cfg ${plan.structure.cfg} seed ${plan.structure.seed} " +
                "${plan.structure.scheduler}, shape ${plan.shape.steps} steps cfg ${plan.shape.cfg} ${plan.shape.scheduler}, crop ${plan.cropSize}, ≤${plan.maxTriangles} tris, est ${"%.0f".format(plan.estMinutes)} min" +
                ", from ${ck.describe(plan.structure.steps, plan.shape.steps)}")
            var last = ""; val t0 = System.currentTimeMillis()
            val times = deviceTimes(ctx, plan)
            val sSteps = if (steps > 0) steps else plan.structure.steps; val hSteps = if (steps > 0) steps else plan.shape.steps
            val eta = app.belle.assistant.core.Pixal3DEta(sSteps, hSteps, times)
            this.eta = eta; Pixal3DService.start(ctx)
            val doneGlb = ck.glb()
            val photo = photoIn ?: if (doneGlb == null && ck.cond() == null)
                app.belle.assistant.core.AtomicFiles.readSealed(File(jobDir(ctx), "photo.ck"))?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    ?: error("the photo of the unfinished run is damaged; start again") else null
            if (doneGlb != null) {   // killed after the model was made but before it was given to the assistant
                if (!lastGlb(ctx).isFile) app.belle.assistant.core.AtomicFiles.write(lastGlb(ctx), doneGlb)
                val imp = importTo(doneGlb)
                discardJob(ctx); lastResult = "3D model finished after a restart. $imp"
                hist.finish(buildId, app.belle.assistant.core.BuildRecord.DONE, System.currentTimeMillis())
                log(ctx, "p3d: done for $name (finished after a restart)"); Pixal3DService.notifyResult(ctx, true, profileId, name, null)
                return lastResult!!
            }
            val g = p3.generate(photo ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), fallbackMask = { app.belle.assistant.avatar.Cutout.matte(it) }, progress = { what, f ->
                if (what == killAt) { killAt = null; log(ctx, "p3d: heap gate: killing the process at $what"); runCatching { app.belle.assistant.core.AtomicFiles.write(File(ctx.filesDir, "heap_gate/killed.txt"), what.toByteArray()) }; android.os.Process.killProcess(android.os.Process.myPid()) }
                if (what != last) { last = what; stage = what; val rss = Port3D.rssMb().toInt(); peak = maxOf(peak, rss)
                    if (!what.matches(Regex(".* \\d+/\\d+"))) runCatching { job.optJSONObject("open_try")?.put("stage", what); saveJob(ctx, job) }
                    hist.progress(buildId, what, rss); log(ctx, "p3d: $what at ${(System.currentTimeMillis() - t0) / 1000} s, rss $rss MB, java heap ${heapMb()} MB, native buffers ${NativeMem.live.get() shr 20} MB") }
                eta.onProgress(what, f)
                // a timed step: save this phone's speed now, so even an interrupted run improves the next estimate
                if (what.startsWith("Rough shape ") || what.startsWith("Detailed shape ")) runCatching { app.belle.assistant.core.AtomicFiles.write(timesFile(ctx), eta.learned(times, finished = false).json().toByteArray()) }
                Avatar3DFiles.progress = what to f * 0.9f; !cancel }, plan = plan, steps = steps, ck = ck)
            ck.saveGlb(g.glb)
            runCatching { timesFile(ctx).writeText(eta.learned(times).json()) }
            app.belle.assistant.core.AtomicFiles.write(lastGlb(ctx), g.glb)
            hist.progress(buildId, "giving the model to $name", Port3D.rssMb().toInt())
            val (imp, st) = Port3D.measure("avatar import") { importTo(g.glb) }
            val all = g.stats + st
            val j = JSONObject().put("ms", g.ms + st.ms).put("peak_mb", all.maxOf { it.peakMb }).put("voxels", g.voxels).put("fov", g.fovDeg)
                .put("vertices", g.vertices).put("triangles", g.triangles).put("seen", g.seen).put("filled", g.filled)
                .put("stages", JSONArray().apply { all.forEach { put(it.json()) } })
                // what ran, and per-call sampling times the next plan's time fit uses
                .put("ss_steps", g.ssParams.steps).put("ss_cfg", g.ssParams.cfg).put("ss_scheduler", g.ssParams.scheduler).put("ss_seed", g.seedSs)
                .put("shape_steps", g.shapeParams.steps).put("shape_cfg", g.shapeParams.cfg).put("shape_scheduler", g.shapeParams.scheduler).put("shape_seed", g.seedShape)
                .put("accel", accel().id).put("ss_calls", FlowSampler.modelCalls(g.ssParams)).put("ss_ms", g.stats.firstOrNull { it.stage.startsWith("ss flow") }?.ms ?: 0)
                .put("shape_calls", FlowSampler.modelCalls(g.shapeParams)).put("shape_ms", g.stats.firstOrNull { it.stage.startsWith("shape512 flow") }?.ms ?: 0)
                .put("crop", plan.cropSize).put("cluster_grid", g.clusterGrid)
            File(dir(ctx), "last_run.json").writeText(j.toString(1))
            discardJob(ctx)   // only after the assistant has the whole new model (Avatar3DFiles.import commits atomically)
            lastResult = "Made a 3D model on this ${app.belle.assistant.core.Platform.device} in ${(g.ms + st.ms) / 1000} s (peak ${all.maxOf { it.peakMb }} MB): ${"%,d".format(g.triangles)} triangles. $imp"
            hist.finish(buildId, app.belle.assistant.core.BuildRecord.DONE, System.currentTimeMillis(), peakMb = maxOf(peak, all.maxOf { it.peakMb }.toInt()))
            log(ctx, "p3d: done for $name: ${"%,d".format(g.triangles)} triangles"); Pixal3DService.notifyResult(ctx, true, profileId, name, null)
            return "Made a 3D model on this ${app.belle.assistant.core.Platform.device} in ${(g.ms + st.ms) / 1000} s (peak ${all.maxOf { it.peakMb }} MB): ${"%,d".format(g.triangles)} triangles. $imp"
        } catch (e: Pixal3D.Cancelled) {
            discardJob(ctx)      // stopped on purpose: nothing to resume
            hist.finish(buildId, app.belle.assistant.core.BuildRecord.CANCELLED, System.currentTimeMillis(), peakMb = peak); log(ctx, "p3d: stopped by you ($name)")
            throw e
        } catch (e: Throwable) {
            // every step here is guarded: after an out-of-memory error even logging may fail, and a failure that isn't
            // written down is what made 0.1.3's retries look like "interrupted"
            val f = app.belle.assistant.core.BuildJobRules.classify(e); lastError = f.message
            val oom = f.kind == app.belle.assistant.core.BuildJobRules.OOM
            if (oom) runCatching { System.gc() }
            runCatching { app.belle.assistant.core.BuildJobRules.recordFailure(job, f, stage, versionCode()); job.remove("open_try"); saveJob(ctx, job) }
            runCatching { hist.finish(buildId, if (oom) app.belle.assistant.core.BuildRecord.OUT_OF_MEMORY else app.belle.assistant.core.BuildRecord.FAILED, System.currentTimeMillis(), f.message, peak) }
            runCatching { log(ctx, "p3d: failed for $name at $stage: ${f.message}" + if (oom) " (java heap ${heapMb()} of ${Runtime.getRuntime().maxMemory() shr 20} MB, native buffers ${NativeMem.live.get() shr 20} MB)" else "") }
            if (oom) runCatching { log(ctx, "p3d: not retrying by itself: the same version would run out of memory the same way; the saved progress is kept for the next app version") }
            loggedFailure = true
            runCatching { Pixal3DService.notifyResult(ctx, false, profileId, name, if (oom) "ran out of memory at $stage. Your progress is kept: update the app, then Resume." else f.message) }
            throw e
        } finally { running = false; Avatar3DFiles.progress = null; eta = null }
    }
}
