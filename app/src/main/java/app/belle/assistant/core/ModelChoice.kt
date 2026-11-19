package app.belle.assistant.core

/**
 * Which thinking model to use right now — one pure rule so the service, the settings page and the reply text agree.
 * The profile's own choice wins when its file is there; otherwise the model last chosen anywhere; otherwise the only
 * (smallest) installed one. "No model" is reported only when nothing is installed, and a chosen-but-missing file is
 * named so the user knows what to fix.
 */
object ModelChoice {
    data class Pick(val file: String?, val note: String) {
        val ok get() = file != null
    }

    fun resolve(profileChoice: String, globalLast: String, installed: List<String>): Pick {
        if (profileChoice.isNotEmpty() && profileChoice in installed) return Pick(profileChoice, "chosen for this assistant")
        if (globalLast.isNotEmpty() && globalLast in installed) return Pick(globalLast,
            if (profileChoice.isEmpty()) "this assistant has none chosen, using your last choice" else "$profileChoice is missing, using your last choice")
        if (installed.isNotEmpty()) return Pick(installed.first(),
            if (profileChoice.isEmpty()) "this assistant has none chosen, using the installed one" else "$profileChoice is missing, using ${installed.first()}")
        return Pick(null, if (profileChoice.isNotEmpty()) "the chosen thinking model ($profileChoice) isn't on the phone any more" else "no thinking model is installed")
    }

    /** What to say when the rules couldn't handle a request and no model can run it. */
    fun noModelSpeech(pick: Pick, loadFailed: String?): String = when {
        loadFailed != null -> "Sorry, my thinking model ($loadFailed) didn't load. Open Settings, Thinking model, and pick it again or choose a smaller one."
        pick.file == null && pick.note.contains("isn't on the phone") -> "Sorry, I didn't understand that, and ${pick.note}. Open Settings, Thinking model, to download it again."
        pick.file == null -> "Sorry, I didn't understand that. Download a thinking model in Settings so I can understand more."
        else -> "Sorry, I didn't understand that."
    }
}

/**
 * Keeps "selected" and "loaded" apart: unloading (idle unload / load-on-demand / low RAM) never clears the choice,
 * and [ensure] loads the resolved model again on the next request instead of reporting that none is selected.
 */
class LlmOnDemand(
    private val installed: () -> List<String>,
    private val profileChoice: () -> String,
    private val globalLast: () -> String,
    private val loadFile: (String) -> Boolean,
    private val unloadFile: () -> Unit,
) {
    @Volatile var loaded: String = ""; private set
    @Volatile var lastFailure: String? = null; private set

    fun pick(): ModelChoice.Pick = ModelChoice.resolve(profileChoice(), globalLast(), installed())

    /** Blocking. Returns the pick; [loaded] == pick.file afterwards unless loading failed ([lastFailure]). */
    @Synchronized fun ensure(): ModelChoice.Pick {
        val p = pick()
        val f = p.file ?: run { if (loaded.isNotEmpty()) unload(); return p }
        if (loaded == f) return p
        if (loaded.isNotEmpty()) { unloadFile(); loaded = "" }
        if (loadFile(f)) { loaded = f; lastFailure = null } else lastFailure = f
        return p
    }

    @Synchronized fun unload() { if (loaded.isNotEmpty()) unloadFile(); loaded = "" }
}
