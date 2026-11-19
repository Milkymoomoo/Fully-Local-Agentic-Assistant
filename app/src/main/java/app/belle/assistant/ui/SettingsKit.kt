package app.belle.assistant.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.core.ModelCatalog
import app.belle.assistant.core.VoiceModel
import app.belle.assistant.models.ModelFiles
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import app.belle.assistant.speech.VoicePreview
import java.io.File

/** A sub-heading inside a section ("Text-to-speech › Voice model"). */
/** The one name we use for the accessibility service — matches what Android shows (Settings › Accessibility). */
const val A11Y_TITLE = "Accessibility (lets me tap/scroll/read the screen)"
const val A11Y_WHY = "Optional — only needed when you ask me to open apps or act on the screen (tap, scroll, type, read what's shown). Everything else works without it."
fun a11ySteps(appName: String) = "Settings › Accessibility › Installed apps (Samsung; called \"Installed services\" or \"Downloaded apps\" on other phones) › $appName › On."

/** Opens Android's accessibility page for this app: the service's own details page where the phone allows it
 *  (Android 11+), else the Accessibility list with our entry highlighted, else the plain Accessibility list. */
fun openAccessibilitySettings(ctx: android.content.Context, appName: String) {
    val comp = android.content.ComponentName(ctx, app.belle.assistant.actions.BelleAccessibilityService::class.java)
    val flat = comp.flattenToString()
    val tries = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 30) add(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, flat))
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key", flat)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", flat) }))
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
    android.widget.Toast.makeText(ctx, a11ySteps(appName), android.widget.Toast.LENGTH_LONG).show()
}

fun lessRestricted(id: String) = ModelCatalog.LLMS.firstOrNull { it.id == id }?.lessRestricted == true
/** Total RAM in GiB (looked up once per process: [app.belle.assistant.models.Downloads.totalRamGb] caches the system call). */
fun ramGb(ctx: android.content.Context): Double = app.belle.assistant.models.Downloads.totalRamGb(ctx) * 1e9 / 1073741824.0
fun llmOf(id: String) = ModelCatalog.LLMS.firstOrNull { it.id == id }


/** Live "what is the listener doing" panel: service state, a mic level meter, Android's silencing flag, and what the
 *  wake-word spotter last heard. Refreshes 5×/s while visible. */
@Composable
fun ListeningStatus(act: MainActivity) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(200); now = System.currentTimeMillis() } }
    val d = AssistantRuntime.Diag
    fun ago(t: Long) = if (t == 0L) "never" else ((now - t) / 1000).let { if (it < 60) "${it}s ago" else "${it / 60} min ago" }
    val running = AssistantService.inForeground
    val receiving = running && now - d.lastNonZeroAt < 1500
    val silenced = AssistantRuntime.micSilenced || d.systemSilenced == true
    val (headline, color) = when {
        !running -> "Not listening — the listener isn't running" to MaterialTheme.colorScheme.error
        !d.micAllowed || silenced -> "Microphone paused by Android" to MaterialTheme.colorScheme.error
        AssistantRuntime.mode == AssistantRuntime.Mode.LOADING -> "Loading the wake-word model…" to MaterialTheme.colorScheme.onSurfaceVariant
        receiving -> "Listening — hearing audio" to Color(0xFF2E7D32)
        else -> "Running, but no sound from the microphone yet" to MaterialTheme.colorScheme.tertiary
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text("Listening status", fontWeight = FontWeight.SemiBold)
        Text(headline, color = color, fontWeight = FontWeight.SemiBold)
        // level meter: -70 dBFS (silence) … -10 dBFS (loud speech)
        val frac = if (!running) 0f else ((d.levelDb + 70f) / 60f).coerceIn(0f, 1f)
        val peak = if (!running) 0f else ((d.peakDb + 70f) / 60f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).size(height = 12.dp, width = 1.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth(frac).size(height = 12.dp, width = 1.dp).background(if (frac > 0.45f) Color(0xFF43A047) else Color(0xFF9E9E9E)))
            Box(Modifier.fillMaxWidth(peak).size(height = 12.dp, width = 1.dp)) { Box(Modifier.align(Alignment.CenterEnd).size(2.dp, 12.dp).background(MaterialTheme.colorScheme.primary)) }
        }
        val mono = MaterialTheme.typography.bodySmall
        Text("Mic level: " + (if (running) "%.0f dB".format(d.levelDb) else "—") + " · last audio " + ago(d.lastAudioAt), style = mono)
        Text("Service: " + (if (running) "running in the foreground" else "stopped") + (if (d.startedFrom.isNotEmpty()) " · started from ${d.startedFrom}" else ""), style = mono)
        Text("Android silencing my recorder: " + when (d.systemSilenced) { true -> "YES"; false -> "no"; null -> "unknown" } +
            (if (AssistantRuntime.micSilenced) " · getting digital silence" else ""), style = mono)
        Text("Wake word: " + (if (act.state.prefs.wakeWordEnabled) AssistantRuntime.status else "off") + " · ${d.kwsChunks} chunks checked", style = mono)
        Text("Spotter last heard: " + (d.lastKwsTokens.ifEmpty { "—" }) + (if (d.lastKwsAt > 0) " (${ago(d.lastKwsAt)})" else ""), style = mono)
        Text("Last wake: " + (if (d.lastWake.isEmpty()) "none yet" else "\"${d.lastWake}\" ${ago(d.lastWakeAt)}") +
            " · last sentence: " + (if (d.lastHeard.isEmpty()) "—" else "\"${d.lastHeard.take(60)}\""), style = mono)
        Text("Score: the on-phone spotter only reports a hit once a phrase passes its threshold (Sensitivity ${(act.state.prefs.sensitivity * 100).toInt()}%); it gives no running score.", style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!running || silenced || !d.micAllowed) FilledTonalButton(onClick = {
            act.state.edit { serviceWanted = true }; AssistantService.start(act, AssistantService.ACTION_REARM, from = "app") }) { BtnText("Start listening now") }
    }
}

@Composable
fun SubHeader(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))

/** What an option needs: size, RAM, download, permissions, Android version, offline. One small line of chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Requires(vararg items: String?) {
    val l = items.filterNotNull().filter { it.isNotBlank() }
    if (l.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        l.forEach { t ->
            Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** An option that can't be used yet: shown greyed with the reason and one button that fixes it. */
@Composable
fun Blocked(reason: String, fix: String?, onFix: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("🔒 $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (fix != null && onFix != null) TextButton(onClick = onFix) { BtnText(fix) }
    }
}

/** A switch that is disabled (with reason + fix button) until its requirement is met. */
@Composable
fun GatedSwitch(title: String, help: String?, checked: Boolean, blockedReason: String?, fix: String?, onFix: (() -> Unit)?, req: List<String?> = emptyList(), onChange: (Boolean) -> Unit) {
    Column {
        if (blockedReason == null) SwitchRow(title, help, checked, onChange)
        else Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            androidx.compose.material3.Switch(checked = false, onCheckedChange = null, enabled = false)
        }
        if (blockedReason != null) Blocked(blockedReason, fix, onFix)
        Requires(*req.toTypedArray())
    }
}

fun fmtMb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

/** Requirement chips for one voice. */
fun voiceReqs(v: VoiceModel, installed: Boolean): List<String> = listOf(
    if (installed) "Installed" else "📦 ${fmtMb(v.approxBytes)} download",
    "🧠 ~${v.ramMb} MB RAM while speaking",
    "🎚 ${v.qualityLabel} quality", "⚡ ${v.speedLabel}",
    "✈️ works offline" + if (installed) "" else " after download",
)

/**
 * Every voice in one list with a ▶ button: installed ones speak live (with the editable phrase), the rest play the
 * official sample. Tapping an installed voice selects it; others offer the one-tap download.
 */
@Composable
fun VoiceList(s: UiState, act: MainActivity, onlyInstalled: Boolean = false) {
    val p = s.prefs
    val installed = ModelFiles.installedVoices(act).toSet()
    var phrase by remember(p.id, p.previewPhrase) { mutableStateOf(p.previewPhrase) }
    OutlinedTextField(phrase, { phrase = it; p.previewPhrase = it }, label = { Text("Preview phrase (installed voices speak it on this phone)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    val known = ModelCatalog.VOICES.map { it.dirName }.toSet()
    val rec = ModelCatalog.recommendedVoice(ramGb(act))
    val all = ModelCatalog.VOICES.sortedBy { if (it.id == rec?.id) 0 else if (it.quality == "high") 1 else 2 }.filter { !onlyInstalled || it.dirName in installed } +
        installed.filter { it !in known }.map { VoiceModel(it, it, "?", "imported", "", it, 0, "") }
    all.forEach { v ->
        val have = v.dirName in installed
        Row(Modifier.fillMaxWidth().clickable(enabled = have) { s.edit { voiceDir = v.dirName }; AssistantRuntime.reloadVoice() }
            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = p.voiceDir == v.dirName, onClick = null, enabled = have)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(v.label, style = MaterialTheme.typography.titleMedium)
                if (v.id == rec?.id) Text("★ Recommended for this phone (%.0f GB RAM) · high quality".format(ramGb(act)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else if (v.quality == "high") Text("High quality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                Text("${v.gender} · ${v.accent}" + if (v.license.isNotEmpty()) " · ${v.license}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.archiveUrl.isNotEmpty()) Requires(*voiceReqs(v, have).toTypedArray())
                if (!have && v.archiveUrl.isNotEmpty() && !VoicePreview.cached(act, v)) Text(if (p.internetEnabled) "▶ plays the official sample (fetched once, then kept)" else "▶ sample needs the internet (off in Settings › Internet & search)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { Thread { VoicePreview.play(act, v, have, phrase) { m -> act.runOnUiThread { act.toast(m) } } }.start() }) { BtnText("▶ Play") }
                if (!have && v.archiveUrl.isNotEmpty()) TextButton(onClick = { s.tab = "settings"; s.page = "getmodels" }) { BtnText("Get") }
            }
        }
    }
}

/** Small cached thumbnails (by file + mtime). */
private object Thumbs {
    private var def: android.graphics.Bitmap? = null
    /** The built-in avatar every profile shows until it gets its own picture. */
    fun builtIn(ctx: android.content.Context) = def ?: BitmapFactory.decodeResource(ctx.resources, app.belle.assistant.R.drawable.avatar_default,
        BitmapFactory.Options().apply { inSampleSize = 4 }).also { def = it }
    private val cache = HashMap<String, android.graphics.Bitmap?>()
    fun get(f: File?, px: Int): android.graphics.Bitmap? {
        if (f == null || !f.isFile) return null
        val key = "${f.path}|${f.lastModified()}|$px"
        return synchronized(cache) { cache.getOrPut(key) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                var ss = 1; while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= px) ss *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss })
            }.getOrNull() } }
    }
}

/** The profile's 3D thumbnail, re-rendered (in the background) when the mesh, shader or framing changed. */
private fun preview3d(ctx: android.content.Context, id: String, onReady: () -> Unit): File? {
    val f = Avatar3DFiles.preview(ctx, id); val mesh = Avatar3DFiles.mesh(ctx, id)
    val pp = ProfileStore.prefsOf(ctx, id)
    val key = "${mesh.lastModified()}|${pp.shader3d.id}|${pp.shader3dIntensity}|${FramingRev.rev}"
    val marker = File(f.path + ".key")
    val fresh = f.isFile && marker.isFile && marker.readText() == key
    if (!fresh && PreviewJobs.mayTry(id) && PreviewJobs.start(id)) Thread {
        val before = f.lastModified()
        runCatching { Avatar3DFiles.renderPreview(ctx, id) }
        val ok = f.isFile && f.lastModified() != before
        if (ok) marker.writeText(key) else PreviewJobs.failed(id)   // failed/timed out: try again a minute later
        PreviewJobs.done(id); if (ok) android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
    }.start()
    return f.takeIf { it.isFile }
}
private object PreviewJobs { private val running = HashSet<String>(); private val failedAt = HashMap<String, Long>()
    fun failed(id: String) = synchronized(running) { failedAt[id] = System.currentTimeMillis() }
    fun mayTry(id: String) = synchronized(running) { System.currentTimeMillis() - (failedAt[id] ?: 0L) > 60_000 }
    fun start(id: String) = synchronized(running) { running.add(id) }; fun done(id: String) = synchronized(running) { running.remove(id) } }

/**
 * Every assistant with a picture. Tap the picture: flips between 2D and 3D (only when it has a 3D model; otherwise
 * it stays 2D with a "No 3D" badge and an Import 3D button). Choosing which assistant answers is the separate
 * "Use" radio, so the two never get mixed up.
 */
@Composable
fun AssistantPicker(s: UiState, act: MainActivity) {
    s.rev
    val ids = ProfileStore.ids(act); val active = ProfileStore.activeId(act)
    var importFor by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = importFor ?: return@rememberLauncherForActivityResult
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val files = uris.associate { act.displayName(it).lowercase() to act.contentResolver.openInputStream(it)!!.use { i -> i.readBytes() } }
        act.toast("Importing the 3D model…")
        Thread {
            val r = runCatching { Avatar3DFiles.import(act, id, files) }
            act.runOnUiThread { r.onSuccess { act.toast(it); ProfileStore.prefsOf(act, id).show3d = true }.onFailure { act.toast("Import failed: ${it.message}") }
                FramingRev.rev++; s.rev++; AssistantWidget.updateAll(act) }
        }.start()
    }
    ids.forEach { id ->
        val pp = ProfileStore.prefsOf(act, id)
        val has3d = Avatar3DFiles.has(act, id)
        val show3d = has3d && pp.show3d
        val img = if (show3d) preview3d(act, id) { s.rev++ } else pp.avatarFile.takeIf { it.isNotEmpty() }?.let { File(act.filesDir, it) }
        val bmp = Thumbs.get(img, 256) ?: Thumbs.get(pp.avatarFile.takeIf { it.isNotEmpty() }?.let { File(act.filesDir, it) }, 256) ?: Thumbs.builtIn(act)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (id == active) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                .clickable { if (has3d) { pp.show3d = !pp.show3d; s.rev++; FramingRev.rev++; AssistantWidget.updateAll(act); if (id == active) AssistantService.start(act, AssistantService.ACTION_AVATAR) }
                             else act.toast("${pp.assistantName} has no 3D model yet — tap Import 3D") }) {
                if (bmp != null) Image(bmp.asImageBitmap(), pp.assistantName, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
                else Text(if (show3d) "3D…" else "🙂", modifier = Modifier.align(Alignment.Center))
                Text(if (show3d) "3D" else if (has3d) "2D" else "No 3D", style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(pp.assistantName, style = MaterialTheme.typography.titleMedium)
                Text(if (has3d) "Tap the picture: 2D ↔ 3D" else "2D only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!has3d) TextButton(onClick = { importFor = id; importer.launch(arrayOf("*/*")) }) { BtnText("Import 3D") }
            }
            Row(Modifier.clickable { ProfileStore.setActive(act, id); AssistantRuntime.reloadWakeWords(act); AssistantWidget.updateAll(act); s.rev++ }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = id == active, onClick = null); Text("Use", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** The one-page Simple settings: everything essential, with requirements and fixes inline. */
@Composable
fun SimpleSettings(s: UiState, act: MainActivity) {
    val p = s.prefs
    Section("Assistant", "Pick who answers with Use. Tap a picture to switch it between 2D and 3D.") { AssistantPicker(s, act) }
    Section("Voice") {
        SubHeader("Text-to-speech › Voice model")
        VoiceList(s, act, onlyInstalled = true)
        if (ModelFiles.installedVoices(act).isEmpty()) Blocked("No voice installed: I can only show replies as text.", "Get voices") { s.page = "getmodels" }
        TextButton(onClick = { s.page = "voice" }) { BtnText("All voices ›") }
        SubHeader("Volume")
        SliderRow("Voice volume", "${(p.voiceVolume * 100).toInt()}%", null, p.voiceVolume, 0f..1f) { v -> s.edit { voiceVolume = v } }
    }
    Section("System prompt", "Who ${p.assistantName} is and how they talk to you. Applies from the next reply, with any model.") {
        SystemPromptEditor(s)
    }
    Section("Thinking model (language model)") {
        MemorySimple(s, act)
        ResetConversationButton(act)
        val llms = ModelFiles.installedLlms(act)
        if (llms.isEmpty()) Blocked("None installed: only simple commands (timers, calls, apps) work.", "Get one") { s.page = "getmodels" }
        llms.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { s.edit { llmFile = f.name }; AssistantRuntime.reloadLlm(act) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.llmFile == f.name, onClick = null)
                val meta = ModelCatalog.LLMS.firstOrNull { it.fileName == f.name }
                Column(Modifier.padding(start = 8.dp)) {
                    Text(meta?.label ?: f.name, style = MaterialTheme.typography.titleMedium)
                    Requires("📦 ${fmtMb(f.length())}", meta?.let { "🧠 needs ${it.minRamGb} GB RAM" }, "✈️ offline")
                }
            }
        }
    }
    Section("Listening") {
        SubHeader("Wake words")
        val mic = act.has(android.Manifest.permission.RECORD_AUDIO)
        GatedSwitch("Wake words on", "Say a wake word to talk to me, even with the screen off.", p.wakeWordEnabled,
            if (mic) null else "Needs the microphone permission.", "Allow", { act.requestCorePermissions() },
            listOf("🎤 microphone", "✈️ offline", "~6 MB RAM")) { s.edit { wakeWordEnabled = it }; if (it) { s.edit { serviceWanted = true }; AssistantService.start(act, AssistantService.ACTION_REARM, from = "app") } }
        PhraseList(p.wakePhrases, "Add a wake word or phrase", emptyList(), onChange = { s.edit { wakePhrases = it }; AssistantRuntime.reloadWakeWords(act) })
        ListeningStatus(act)
        SubHeader("Continuous conversation")
        GatedSwitch("Keep listening after I answer", "Talk again without the wake word; I keep listening (no time limits) until you say a sleep word or press End.", p.continuous,
            if (ModelFiles.hasStt(act)) null else "Needs speech recognition (30 MB download).", "Get it", { s.page = "getmodels" },
            listOf("Saved per assistant", "Also on the widget")) { s.edit { continuous = it }; AssistantWidget.updateAll(act) }
    }
    Section("Avatar", "Avatar Studio walks you through it: assistant, picture, 2D or 3D, download, build.") {
        NavRow("🎨", "Avatar Studio", Studio.summary(act) ?: "Make a new avatar, step by step") { s.page = "studio" }
        val has3d = Avatar3DFiles.has(act, p.id)
        GatedSwitch("Show my 3D avatar", "On Home, the bubble and in the picker above.", has3d && p.show3d,
            if (has3d) null else "${p.assistantName} has no 3D model yet.", "Make one", { s.page = "studio" }, listOf("GPU (OpenGL ES 3)", "✈️ offline")) {
            s.edit { show3d = it }; FramingRev.rev++; AssistantWidget.updateAll(act); AssistantService.start(act, AssistantService.ACTION_AVATAR) }
        TextButton(onClick = { s.page = "avatar3d" }) { BtnText("Import a 3D model ›") }
    }
    Section("Theme") {
        ChoiceRow("Theme", null, listOf("dark" to "Dark", "light" to "Light", "system" to "Follow phone"), p.theme) { s.edit { theme = it } }
    }
    Section("Permissions") {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
        PermRow("Microphone", act.has(android.Manifest.permission.RECORD_AUDIO), "Allow") { act.requestCorePermissions() }
        PermRow("Display over other apps (avatar bubble, opening apps)", Settings.canDrawOverlays(act), "Allow") {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))) }
        val pm = act.getSystemService(android.os.PowerManager::class.java)
        PermRow("Run in the background (battery unrestricted)", pm.isIgnoringBatteryOptimizations(act.packageName), "Allow") {
            if (runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }.isFailure) act.startActivity(details) }
        PermRow(A11Y_TITLE, app.belle.assistant.actions.BelleAccessibilityService.instance != null, "Turn on") {
            openAccessibilitySettings(act, p.assistantName) }
        Help("$A11Y_WHY\nHow: ${a11ySteps(p.assistantName)}")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Help("Android 13+: if Accessibility says \"Restricted setting\", open App info › ⋮ › Allow restricted settings.")
            OutlinedButton(onClick = { act.startActivity(details); act.toast("⋮ (top right) › Allow restricted settings") }) { BtnText("Allow restricted settings") }
        }
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, button: String, go: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️", modifier = Modifier.width(32.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) FilledTonalButton(onClick = go) { BtnText(button) }
    }
}
