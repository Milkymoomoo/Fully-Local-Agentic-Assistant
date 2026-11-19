package app.belle.assistant.core

import org.json.JSONObject
import java.io.File

/**
 * Avatar Studio (Simple mode): a guided path from "which assistant" to a finished avatar assigned to it.
 *   1 Assistant  ·  2 Picture  ·  3 2D or 3D  ·  4 Download all  ·  5 Build
 * The wizard's state is saved after every choice (atomic + checksummed, two slots, see [CheckpointStore]) so it
 * reopens where you left off, also after a kill or a restart. The rules for which step is done / next / waiting
 * live here, without Android, so they are unit-tested and reusable by a desktop port.
 */
data class StudioState(val profileId: String? = null, val picture: String? = null, val kind: String? = null,
                       val buildStarted: Boolean = false, val finished: Boolean = false, val step: Int = 1, val startedAt: Long = 0) {
    fun json(): String = JSONObject().put("profile", profileId).put("picture", picture).put("kind", kind)
        .put("buildStarted", buildStarted).put("finished", finished).put("step", step).put("startedAt", startedAt).toString()

    companion object {
        fun parse(s: String): StudioState = JSONObject(s).let { o ->
            fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            StudioState(str("profile"), str("picture"), str("kind")?.takeIf { it == "2d" || it == "3d" },
                o.optBoolean("buildStarted"), o.optBoolean("finished"), o.optInt("step", 1).coerceIn(1, 5), o.optLong("startedAt"))
        }
        fun load(store: CheckpointStore): StudioState = store.load("studio")?.let { runCatching { parse(String(it.second)) }.getOrNull() } ?: StudioState()
        /** Each save is a new checkpoint step, so the newest valid one wins and a damaged write falls back to the one before. */
        fun save(store: CheckpointStore, s: StudioState) { store.save("studio", ((store.load("studio")?.first ?: 0) + 1), s.json().toByteArray()) }
        fun store(filesDir: File) = CheckpointStore(File(filesDir, "avatar_studio"))
    }
}

object StudioFlow {
    enum class St { DONE, NEXT, TODO, WAITING }
    data class Step(val n: Int, val title: String, val state: St, val why: String? = null)

    /** What is true on the device right now (read by the UI each time). */
    data class Facts(val profileExists: Boolean, val pictureExists: Boolean, val toolsReady: Boolean, val toolsInstalling: Boolean,
                     val building: Boolean, val avatarAssigned: Boolean, val buildFailed: String? = null)

    /** The five steps and their states. A step is NEXT only when everything before it is done. */
    fun steps(s: StudioState, f: Facts): List<Step> {
        val d1 = s.profileId != null && f.profileExists
        val d2 = d1 && s.picture != null && f.pictureExists
        val d3 = d2 && s.kind != null
        val d4 = d3 && f.toolsReady
        val d5 = d4 && s.finished && f.avatarAssigned
        fun st(done: Boolean, prevDone: Boolean) = when { done -> St.DONE; prevDone -> St.NEXT; else -> St.WAITING }
        return listOf(
            Step(1, "Choose the assistant", st(d1, true)),
            Step(2, "Pick a picture", st(d2, d1), if (!d1) "choose the assistant first" else null),
            Step(3, "2D or 3D", st(d3, d2), if (!d2) "pick a picture first" else null),
            Step(4, "Download all", st(d4, d3), when { !d3 -> "choose 2D or 3D first"; f.toolsInstalling -> "downloading now"; else -> null }),
            Step(5, "Build the avatar", st(d5, d4), when {
                !d4 -> if (d3) "finish step 4 first" else "earlier steps first"
                f.building -> "building now (it continues in the background and after a restart)"
                f.buildFailed != null -> "the last try failed: ${f.buildFailed}"
                else -> null }),
        )
    }

    /** The step to show open: the first one that isn't done (or the last). */
    fun current(steps: List<Step>): Int = steps.firstOrNull { it.state != St.DONE }?.n ?: steps.last().n
}
