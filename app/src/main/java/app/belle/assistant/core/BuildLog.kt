package app.belle.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History of photo → 3D builds, kept on the phone so a run you didn't watch still tells you what happened:
 * start / end time, which assistant it was for, the furthest stage, the result or the error, how long it took and the
 * peak memory. Written atomically after every change (see [AtomicFiles]), newest last, at most [KEEP] runs.
 * A run still marked "running" when no build is running was cut off (app killed, battery, reboot): [settle] marks it
 * "interrupted" so the UI can offer Resume. Pure Kotlin + org.json, so it is unit-tested and a desktop port reuses it.
 */
data class BuildRecord(
    val id: Long, val profileId: String, val profileName: String, val startedAt: Long,
    val endedAt: Long? = null, val stage: String = "starting", val result: String = RUNNING,
    val error: String? = null, val peakMb: Int = 0, val resumes: Int = 0, val activeMs: Long = 0,
    /** Why an interrupted run stopped, when Android told us (ApplicationExitInfo), e.g. "it crashed (native code)". */
    val reason: String? = null,
) {
    val durationMs: Long? get() = endedAt?.let { it - startedAt }
    val finished get() = result == DONE || result == FAILED || result == CANCELLED || result == OUT_OF_MEMORY
    /** The error (this try's or an earlier one's) was running out of memory. */
    val outOfMemory get() = result == OUT_OF_MEMORY || (error?.let { BuildJobRules.classify(RuntimeException(it)).kind == BuildJobRules.OOM || it.startsWith("out of memory") } ?: false)

    fun json(): JSONObject = JSONObject().put("id", id).put("profile", profileId).put("name", profileName).put("started", startedAt)
        .put("ended", endedAt ?: JSONObject.NULL).put("stage", stage).put("result", result).put("error", error ?: JSONObject.NULL)
        .put("peak_mb", peakMb).put("resumes", resumes).put("active_ms", activeMs).put("reason", reason ?: JSONObject.NULL)

    /** One line for the screen and the exported log. [fmt] turns a time into text. */
    fun line(fmt: (Long) -> String = ::clock): String {
        val dur = durationMs?.let { " (${BuildLog.duration(it)})" } ?: ""
        val end = endedAt?.let { " → ${fmt(it)}" } ?: ""
        val what = when (result) {
            DONE -> "done"; FAILED -> "failed at $stage: ${error ?: "unknown error"}"; CANCELLED -> "stopped at $stage"
            OUT_OF_MEMORY -> "ran out of memory at $stage: ${error ?: "unknown"}"
            // 0.1.3 left an out-of-memory failure as "interrupted" when the tries after it ended with no result
            INTERRUPTED -> if (outOfMemory) "ran out of memory (${error!!.removePrefix("out of memory: ")}); the tries after it ended without a result at $stage"
                else "interrupted at $stage (${reason ?: "app closed, battery or restart"})"
            else -> "running: $stage" }
        val peak = if (peakMb > 0) " · peak ${"%.1f".format(peakMb / 1024.0)} GB RAM" else ""
        val res = if (resumes > 0) " · resumed $resumes×" else ""
        return "${fmt(startedAt)}$end$dur · for $profileName · $what$peak$res"
    }

    companion object {
        const val RUNNING = "running"; const val DONE = "done"; const val FAILED = "failed"
        const val CANCELLED = "cancelled"; const val INTERRUPTED = "interrupted"; const val OUT_OF_MEMORY = "out_of_memory"
        fun from(o: JSONObject) = BuildRecord(o.getLong("id"), o.getString("profile"), o.optString("name", o.getString("profile")),
            o.getLong("started"), if (o.isNull("ended")) null else o.optLong("ended"), o.optString("stage", "starting"),
            o.optString("result", RUNNING), if (o.isNull("error")) null else o.optString("error"), o.optInt("peak_mb"), o.optInt("resumes"), o.optLong("active_ms"),
            if (o.isNull("reason")) null else o.optString("reason").ifEmpty { null })
        fun clock(t: Long): String = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(t))
    }
}

class BuildLog(private val file: File, private val events: File = File(file.parentFile, file.nameWithoutExtension + "_events.log")) {
    @Synchronized fun all(): List<BuildRecord> = runCatching {
        val b = AtomicFiles.readSealed(file) ?: return emptyList()
        val a = JSONArray(String(b)); (0 until a.length()).map { BuildRecord.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<BuildRecord>) =
        AtomicFiles.writeSealed(file, JSONArray().apply { list.takeLast(KEEP).forEach { put(it.json()) } }.toString().toByteArray())

    /** A new run for [profileId] ([profileName] is stored too, so the log still reads well if the assistant is renamed). */
    @Synchronized fun start(id: Long, profileId: String, profileName: String, now: Long): BuildRecord =
        BuildRecord(id, profileId, profileName, now).also { save(all() + it) }

    /** The same run continues after an interruption. */
    @Synchronized fun resumed(id: Long): BuildRecord? = edit(id) { it.copy(result = BuildRecord.RUNNING, endedAt = null, resumes = it.resumes + 1) }

    @Synchronized fun progress(id: Long, stage: String, rssMb: Int): BuildRecord? =
        edit(id) { it.copy(stage = stage, peakMb = maxOf(it.peakMb, rssMb)) }

    @Synchronized fun finish(id: Long, result: String, now: Long, error: String? = null, peakMb: Int = 0): BuildRecord? =
        edit(id) { it.copy(result = result, endedAt = now, error = error, peakMb = maxOf(it.peakMb, peakMb)) }

    /** Runs left "running" while nothing runs were cut off: mark them interrupted (the end time = when we noticed). */
    @Synchronized fun settle(isRunning: Boolean, now: Long, reason: String? = null) {
        if (isRunning) return
        val l = all(); if (l.none { it.result == BuildRecord.RUNNING }) return
        save(l.map { if (it.result == BuildRecord.RUNNING) it.copy(result = BuildRecord.INTERRUPTED, endedAt = now, reason = reason ?: it.reason) else it })
    }

    fun last(): BuildRecord? = all().lastOrNull()
    fun byId(id: Long) = all().firstOrNull { it.id == id }

    private fun edit(id: Long, f: (BuildRecord) -> BuildRecord): BuildRecord? {
        val l = all(); val i = l.indexOfFirst { it.id == id }; if (i < 0) return null
        val n = f(l[i]); save(l.toMutableList().also { it[i] = n }); return n
    }

    // ---- the build's own event lines (kept on disk, so they survive the kill they help explain) ----
    @Synchronized fun event(now: Long, msg: String) {
        runCatching {
            val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}  ${BuildLog.redact(msg)}\n"
            val old = if (events.isFile) events.readLines().takeLast(EVENT_LINES - 1) else emptyList()
            AtomicFiles.write(events, (old.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" } + line).toByteArray())
        }
    }
    fun events(): List<String> = runCatching { events.readLines() }.getOrDefault(emptyList())
    fun clear() { file.delete(); events.delete() }

    companion object {
        const val KEEP = 30; const val EVENT_LINES = 300
        fun duration(ms: Long): String { val m = (ms + 59_999) / 60_000; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min".replace(" 0 min", "") }
        /** Strip what the log doesn't need: paths under the user's storage and content URIs (they can contain names). */
        fun redact(s: String): String = s
            .replace(Regex("content://\\S+"), "content://…")
            .replace(Regex("/(?:storage|sdcard)/\\S+"), "/storage/…")
            .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "…@…")

        /** Only these app events go into an exported log: the 3D build, installs and downloads, errors. No speech, no replies. */
        private val EXPORT_EVENT = Regex("^(p3d|pixal3d|3d|install|download|models?|error|crash|boot|service|widget|avatar)\\b", RegexOption.IGNORE_CASE)
        fun exportableEvents(lines: List<String>): List<String> = lines.filter { EXPORT_EVENT.containsMatchIn(it.trim()) }.map(::redact)

        /** The text report: device, app, history, the build's events, the last run's measurements, filtered app events. */
        fun report(header: List<String>, history: List<BuildRecord>, pending: String?, buildEvents: List<String>, lastRunJson: String?, appEvents: List<String>): String = buildString {
            appendLine("Milky Moo's Assistant · photo → 3D build log"); header.forEach { appendLine(it) }; appendLine()
            appendLine("== Build history (newest first) ==")
            if (history.isEmpty()) appendLine("(no builds recorded)") else history.asReversed().forEach { appendLine("- " + it.line()) }
            appendLine(); appendLine("== Unfinished build =="); appendLine(pending ?: "(none)")
            appendLine(); appendLine("== Build events ==")
            if (buildEvents.isEmpty()) appendLine("(none)") else buildEvents.forEach { appendLine(redact(it)) }
            appendLine(); appendLine("== last_run.json =="); appendLine(lastRunJson ?: "(no finished run)")
            appendLine(); appendLine("== Recent app events (3D, installs, downloads, errors only) ==")
            val ev = exportableEvents(appEvents); if (ev.isEmpty()) appendLine("(none)") else ev.forEach { appendLine(it) }
        }
    }
}

/**
 * The newest generated model (last_generated.glb) and whether it was given to an assistant. A model that exists but was
 * never attached (older versions could lose track of it, or the target assistant was deleted) is an orphan the UI
 * offers to attach.
 */
object OrphanModel {
    /** [attachedJson] = contents of last_generated.attached ({"sha256":…, "profile":…}) or null. */
    fun isOrphan(glbSha256: String?, attachedJson: String?): Boolean {
        if (glbSha256 == null) return false
        val a = runCatching { JSONObject(attachedJson ?: return true) }.getOrNull() ?: return true
        return a.optString("sha256") != glbSha256
    }
    fun attachedJson(sha256: String, profileId: String, now: Long): String =
        JSONObject().put("sha256", sha256).put("profile", profileId).put("at", now).toString()
}
