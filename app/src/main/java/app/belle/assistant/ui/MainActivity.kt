package app.belle.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.belle.assistant.Prefs
import app.belle.assistant.models.ModelImport
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import java.util.concurrent.Executors

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

/** Home, Conversation and Settings. Jetpack Compose + Material 3. */
class MainActivity : ComponentActivity() {
    lateinit var state: UiState
    private val bg = Executors.newSingleThreadExecutor()
    private val listener: () -> Unit = { state.tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // safe mode: a background that crashed the decoder last time is switched off before anything draws it
        app.belle.assistant.avatar.Backgrounds.recoverAfterCrash(this)?.let { name ->
            android.widget.Toast.makeText(this, "The background \"$name\" stopped the app last time, so I switched back to the picture's own background.", android.widget.Toast.LENGTH_LONG).show() }
        AssistantRuntime.init(this)
        app.belle.assistant.threed.HeapGate.maybeRun(this)   // only when the release gate put a request in files/heap_gate (root only)
        state = UiState(Prefs(this))
        if (savedInstanceState == null) applyIntent(intent)   // a recreate must not re-open the page the launch asked for
        setContent {
            val s = state
            s.rev
            BelleTheme(s.prefs.theme, s.prefs.largeText, s.prefs.wallpaperColors) { Root(s, this) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); applyIntent(intent) }
    override fun onStart() { super.onStart(); onScreen = true }
    override fun onStop() { onScreen = false; super.onStop() }
    override fun onResume() {
        super.onResume(); onScreen = true; AssistantRuntime.addListener(listener); state.tick++; state.rev++
        // Visible now, so Android allows the microphone service: (re)start it, or re-arm a recorder Android silenced.
        if (state.prefs.serviceWanted && has(Manifest.permission.RECORD_AUDIO)) AssistantService.start(this, AssistantService.ACTION_REARM)
        // Unfinished downloads the user started (app was killed, phone restarted): carry on now that we're visible.
        val appCtx = applicationContext
        Thread { runCatching { app.belle.assistant.models.DownloadService.resumeAll(appCtx) }
            // a force stop also clears alarms: re-arm saved reminders (same request codes, so this only replaces them)
            runCatching { app.belle.assistant.service.Reminders.rescheduleAll(appCtx) } }.start()
        // a photo → 3D run that was killed (app swiped away, battery died, restart): continue from its last checkpoint
        runCatching { app.belle.assistant.threed.Pixal3DService.resumeIfPending(this) }
    }
    override fun onPause() { AssistantRuntime.removeListener(listener); super.onPause() }

    private fun applyIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("tab")?.let { state.tab = it; state.page = "" }
        i.getStringExtra("page")?.let { state.tab = "settings"; state.page = it }
        // tapped "Milly's 3D avatar is ready": the 3D page offers to make her active right at the top
        i.getStringExtra("p3d_profile")?.let { BuildPrep.justBuilt = it; state.rev++ }
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)) as Uri?
            if (uri != null) importFile(uri)
        }
    }

    fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** Import a .gguf model or .tar.bz2 voice from the file picker or the Downloader app. No network involved. */
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
                    if (kind.startsWith("voice:")) { state.edit { voiceDir = kind.removePrefix("voice:") }; AssistantRuntime.reloadVoice() }   // used from the next reply; the listener keeps running
                    toast("Imported ${kind.substringAfter(':')}")
                }.onFailure { toast("Import failed: ${it.message}") }
                state.rev++
            }
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun corePermissions(): List<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        return perms
    }
    fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun requestCorePermissions() {
        val missing = corePermissions().filter { !has(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
    @Deprecated("platform callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); state.rev++
    }

    fun startAssistant() { requestCorePermissions(); AssistantService.start(this) }
    companion object {
        /** True while one of our screens is on screen: starts from here count as user-visible for the microphone. */
        @Volatile var onScreen = false
    }
}

@Composable
fun Root(s: UiState, act: MainActivity) {
    BackHandler(enabled = s.page.isNotEmpty() || s.tab != "home") { if (s.page.isNotEmpty()) s.page = "" else s.tab = "home" }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = s.tab == "home", onClick = { s.tab = "home" }, icon = { Icon(Icons.Filled.Home, null) }, label = { BtnText("Home") })
            NavigationBarItem(selected = s.tab == "chat", onClick = { s.tab = "chat" }, icon = { Icon(Icons.Filled.Face, null) }, label = { BtnText("Conversation") })
            NavigationBarItem(selected = s.tab == "settings", onClick = { s.tab = "settings"; s.page = "" }, icon = { Icon(Icons.Filled.Settings, null) }, label = { BtnText("Settings") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (s.tab) {
                "chat" -> ChatScreen(s, act)
                "settings" -> SettingsScreen(s, act)
                else -> HomeScreen(s, act)
            }
        }
    }
}
