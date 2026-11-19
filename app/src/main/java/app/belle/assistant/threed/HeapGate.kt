package app.belle.assistant.threed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The release gate for memory (scripts/heap_gate.sh): the whole photo → 3D → rigged, attached avatar run inside the
 * release app on an emulator whose Java heap is capped at 512 MB (dalvik.vm.heapgrowthlimit = heapsize = 512m, like the
 * OUKITEL WP100 TITAN where 0.1.3 died building the surface), plus a resume from a checkpoint taken after the shape
 * stage under the same cap. The gate script then rewrites that job as 0.1.3 left it (no app_version,
 * tries used 3 of 3) so the resume is the same path as an app update on a phone that still has a 0.1.3
 * save (tries reset, then the surface). No release ships unless it passes (scripts/release.sh runs it).
 *
 * It does nothing unless files/heap_gate/request.json exists. Only the app itself or root (adb root on an emulator) can
 * write there, so no other app can start it. Requests:
 *   {"mode": "full", "steps": 1, "kill_at": "Building the surface"?}
 *       makes a "Heap gate" assistant and builds it from files/heap_gate/photo.png; with kill_at the process kills itself
 *       when that stage starts (a real process death, like Android killing the app), leaving a post-shape checkpoint
 *   {"mode": "resume"}  continues the unfinished build (the app's own resume path), then checks the result
 * Writes files/heap_gate/result.json: ok, error, heap limit, per-stage peak Java heap / RSS / native buffers
 * (last_run.json), the import's peaks, and whether the avatar is rigged and attached.
 */
object HeapGate {
    fun dir(ctx: Context) = File(ctx.filesDir, "heap_gate")
    @Volatile private var started = false

    fun maybeRun(ctx: Context) {
        val req = File(dir(ctx), "request.json"); if (!req.isFile || started) return
        val r = runCatching { JSONObject(req.readText()) }.getOrNull(); req.delete(); if (r == null) return
        started = true
        Thread({ try { run(ctx.applicationContext, r) } finally { started = false } }, "heap-gate").start()
    }

    private fun run(ctx: Context, r: JSONObject) {
        val d = dir(ctx); val out = JSONObject().put("mode", r.optString("mode")).put("started", System.currentTimeMillis())
            .put("heap_limit_mb", Runtime.getRuntime().maxMemory() shr 20).put("version", app.belle.assistant.BuildConfig.VERSION_NAME)
            .put("debuggable", (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        fun write(o: JSONObject) = app.belle.assistant.core.AtomicFiles.write(File(d, "result.json"), o.toString(1).toByteArray())
        write(out.put("state", "running"))
        try {
            val pid: String
            when (r.optString("mode")) {
                "full" -> {
                    pid = app.belle.assistant.ProfileStore.ids(ctx).firstOrNull { app.belle.assistant.ProfileStore.name(ctx, it) == "Heap gate" }
                        ?: app.belle.assistant.ProfileStore.create(ctx, "Heap gate")
                    val photo = android.graphics.BitmapFactory.decodeFile(File(d, "photo.png").path) ?: error("no photo.png in heap_gate/")
                    r.optString("kill_at").takeIf { it.isNotEmpty() }?.let { Pixal3DJob.killAt = it }
                    out.put("result", Pixal3DJob.run(ctx, pid, photo, steps = r.optInt("steps", 1)))
                }
                "resume" -> {
                    // Android's sticky restart may already have resumed (or finished) it before this request was read
                    pid = Pixal3DJob.pendingProfile(ctx) ?: app.belle.assistant.ProfileStore.ids(ctx).firstOrNull { app.belle.assistant.ProfileStore.name(ctx, it) == "Heap gate" }
                        ?: error("nothing to resume")
                    out.put("resumed_from", Pixal3DJob.pendingSummary(ctx))
                    // the app's own path: opening the app (MainActivity) or Android's sticky restart resumes it; nudge it the same way
                    val deadline = System.currentTimeMillis() + 6 * 3600_000L
                    Thread.sleep(5000)
                    while (Pixal3DJob.pendingJob(ctx) != null) {
                        check(System.currentTimeMillis() < deadline) { "resume took over 6 h" }
                        if (!Pixal3DJob.running) {
                            if (!Pixal3DJob.canResume(ctx)) error("it stopped and won't resume: ${Pixal3DJob.lastError ?: Pixal3DJob.resumeBlocked(ctx)}")
                            Pixal3DService.resumeIfPending(ctx)
                        }
                        Thread.sleep(3000)
                    }
                    out.put("result", Pixal3DJob.lastResult ?: "finished")
                }
                else -> error("unknown mode")
            }
            val info = app.belle.assistant.avatar.Avatar3DFiles.info(ctx, pid)
            val attached = app.belle.assistant.avatar.Avatar3DFiles.has(ctx, pid) && app.belle.assistant.Prefs(ctx, pid).show3d
            val rigged = (info?.optInt("bones") ?: 0) > 0
            out.put("attached", attached).put("rigged", rigged).put("avatar", info)
            out.put("last_run", runCatching { JSONObject(Pixal3DJob.lastRun(ctx).readText()) }.getOrNull())
            out.put("events", JSONArray(Pixal3DJob.history(ctx).events().takeLast(80)))
            val stages = out.optJSONObject("last_run")?.optJSONArray("stages")
            val maxHeap = (0 until (stages?.length() ?: 0)).maxOfOrNull { stages!!.getJSONObject(it).optInt("heap_mb") } ?: -1
            out.put("max_stage_heap_mb", maxHeap)
            val rec = Pixal3DJob.history(ctx).last()
            out.put("history_last", rec?.line()).put("resumes", rec?.resumes ?: 0)
            out.put("ok", attached && rigged && Pixal3DJob.pendingJob(ctx) == null && rec?.result == app.belle.assistant.core.BuildRecord.DONE &&
                (r.optString("mode") != "resume" || (rec.resumes) >= 1))
        } catch (e: Throwable) {
            out.put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
            runCatching { out.put("events", JSONArray(Pixal3DJob.history(ctx).events().takeLast(80))) }
        }
        write(out.put("state", "done").put("ended", System.currentTimeMillis()))
    }
}
