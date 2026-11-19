package app.belle.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.belle.assistant.Prefs

/** Shared, observable UI state: bump [rev] after writing prefs, [tick] when the assistant changes state. */
class UiState(val prefs: Prefs) {
    /** Same counter as [app.belle.assistant.SettingsRev]: bumping it redraws everything that shows a setting. */
    var rev by app.belle.assistant.SettingsRev.state
    var tick by mutableIntStateOf(0)
    var tab by mutableStateOf("home")
    /** Settings sub-screen ("" = settings list). */
    var page by mutableStateOf("")
    var busy by mutableStateOf<String?>(null)
    fun edit(f: Prefs.() -> Unit) { prefs.f(); rev++ }
}
