package app.belle.assistant.core

import org.json.JSONObject

/**
 * The rules for an unfinished photo → 3D build (files/pixal3d_job/job.json), kept pure so they are unit-tested:
 * what a failure was, whether trying again can help, and what an app update changes.
 *
 * Real case (0.1.3, OUKITEL WP100 TITAN): try 1 finished the 2-hour shape stages and then ran out of Java heap building
 * the surface; tries 2 and 3 each ended within 20 s without a logged error, so the job sat at "tries used 3 of 3" with a
 * finished detailed shape in its checkpoint. Now:
 *  - an out-of-memory failure is recorded as such (history and job), not as "interrupted";
 *  - trying the same version again after a Java-heap OOM is not done automatically (it would fail the same way and burn
 *    a try): the job waits for an app update, and says so;
 *  - after an app update the tries start again from 0, so the checkpoint (same format since 0.1.3) is resumed;
 *  - a try that ended with no result (process killed or crashed) is noticed at the next start and logged with the
 *    reason Android gives.
 * Job fields read here: attempts, app_version (missing = 0.1.3, version code 4), failure {kind, stage, message, app_version}.
 */
object BuildJobRules {
    const val OOM = "out_of_memory"; const val ERROR = "error"; const val NO_RESULT = "no_result"
    /** Version code of 0.1.3, the last version that did not write app_version into the job. */
    const val UNVERSIONED = 4

    data class Failure(val kind: String, val message: String)

    /** Java heap exhaustion (or no native memory for a buffer) vs any other error. */
    fun classify(e: Throwable): Failure {
        val chain = generateSequence(e) { it.cause }.toList()
        val msg = e.message ?: e.toString()
        val oom = chain.any { it is OutOfMemoryError } || chain.any { (it.message ?: "").let { m -> m.startsWith("Failed to allocate a ") || m.startsWith("no Java heap") || m.startsWith("no native memory") } }
        return if (oom) Failure(OOM, "out of memory: $msg") else Failure(ERROR, msg)
    }

    /** The job was made by an older app version: start counting tries again (the update may well fix what failed). Returns the log line, or null. */
    fun afterUpdate(job: JSONObject, versionCode: Int, versionName: String): String? {
        val was = job.optInt("app_version", UNVERSIONED)
        if (was >= versionCode) return null
        val tries = job.optInt("attempts")
        job.put("attempts", 0).put("app_version", versionCode).put("updated_from", was)
        job.remove("failure")
        return "the app was updated (version code $was → $versionCode, $versionName): tries reset from $tries to 0, the unfinished build can resume"
    }

    /** Why it must not resume by itself now, or null when it may. */
    fun blocked(job: JSONObject, versionCode: Int, maxAttempts: Int): String? {
        val f = job.optJSONObject("failure")
        if (f != null && f.optString("kind") == OOM && f.optInt("app_version", UNVERSIONED) >= versionCode)
            return "it ran out of memory at ${f.optString("stage", "a step")}; trying this same version again would fail the same way. Update the app, then Resume"
        if (job.optInt("attempts") >= maxAttempts) return "it failed $maxAttempts times"
        return null
    }

    /** Remember what ended this try (written into the job, so the next try and the UI know). */
    fun recordFailure(job: JSONObject, f: Failure, stage: String, versionCode: Int) {
        job.put("failure", JSONObject().put("kind", f.kind).put("stage", stage).put("message", f.message.take(300)).put("app_version", versionCode))
    }

    /** Text for Android's ApplicationExitInfo reason codes (API 30+), for "the last try ended because …". */
    fun exitReason(reason: Int, description: String?, status: Int): String {
        val what = when (reason) {
            1 -> "it exited by itself"; 2 -> "Android signalled it (signal $status)"; 3 -> "Android ended it for low memory"
            4 -> "it crashed (Java exception)"; 5 -> "it crashed (native code)"; 6 -> "it stopped responding (ANR)"
            7 -> "it failed to start"; 8 -> "a permission changed"; 9 -> "it used too many resources"
            10 -> "it was closed from the recent apps or by a user request"; 11 -> "you force-stopped it"; 12 -> "a process it depends on died"
            13 -> "Android ended it (other reason)"; 14 -> "it was frozen"; 15 -> "the app's state changed (disabled or similar)"
            16 -> "the app was updated"
            else -> "unknown reason ($reason)" }
        return what + (description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }
}
