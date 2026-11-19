package app.belle.assistant.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import app.belle.assistant.Prefs
import app.belle.assistant.models.ModelImport
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import app.belle.desktop.ui.Toasts
import java.io.File
import java.util.concurrent.Executors

/**
 * Desktop twin of the phone's MainActivity: the settings / conversation window. The shared screens (Settings and
 * all its pages, Home, Conversation) take it as their `act`, so it offers the same members: toasts, importing a
 * picked file, permissions (always granted on a PC: Windows asks for the microphone itself) and starting the
 * assistant.
 */
class MainActivity : ComponentActivity() {
    val state = UiState(Prefs(this))
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "import").apply { isDaemon = true } }

    fun displayName(uri: Uri): String = uri.lastPathSegment ?: "file"

    /** Import a .gguf model or .tar.bz2 voice (or other pack) from a file on the PC. No network involved. */
    fun importFile(uri: Uri) {
        val name = displayName(uri)
        state.busy = "Importing $name…"
        bg.execute {
            val r = ModelImport.importAny(this, uri, name) { done -> runOnUiThread { state.busy = "Importing $name… ${done / 1_000_000} MB" } }
            runOnUiThread {
                state.busy = null
                r.onSuccess { kind ->
                    if (kind.startsWith("model:")) { state.edit { llmFile = kind.removePrefix("model:") }; AssistantRuntime.reloadLlm(this) }
                    if (kind.startsWith("stt:") || kind.startsWith("vad:")) AssistantService.start(this, AssistantService.ACTION_RELOAD)
                    if (kind.startsWith("voice:")) { state.edit { voiceDir = kind.removePrefix("voice:") }; AssistantRuntime.reloadVoice() }
                    toast("Imported ${kind.substringAfter(':')}")
                }.onFailure { toast("Import failed: ${it.message}") }
                state.rev++
            }
        }
    }

    fun toast(s: String) = Toasts.show(s)
    fun corePermissions(): List<String> = emptyList()
    fun has(p: String) = true
    fun requestCorePermissions() {}
    fun startAssistant() = AssistantService.start(this)

    companion object {
        /** True while the settings window is open (the phone's "one of our screens is visible"). */
        @Volatile var onScreen = false
        val instance by lazy { MainActivity() }
    }
}
