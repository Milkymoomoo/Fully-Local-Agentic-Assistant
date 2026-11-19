package app.belle.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.belle.assistant.ProfileStore
import app.belle.assistant.core.BuildLog
import app.belle.assistant.core.BuildRecord
import app.belle.assistant.threed.Pixal3DJob
import app.belle.assistant.threed.Pixal3DService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One assistant out of all of them (radio list). Used to pick who a 3D build or an orphan model goes to. */
@Composable
fun AssistantChoice(act: MainActivity, selected: String?, enabled: Boolean = true, onPick: (String) -> Unit) {
    ProfileStore.ids(act).forEach { id ->
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onPick(id) }.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == id, enabled = enabled, onClick = { onPick(id) })
            Text(ProfileStore.name(act, id) + if (id == ProfileStore.activeId(act)) " (active)" else "", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Keeps the screen on while a 3D build runs and this screen is visible (released when it ends or the screen is left). */
@Composable
fun KeepScreenOnWhileBuilding(act: MainActivity) {
    var on by remember { mutableStateOf(Pixal3DJob.running) }
    LaunchedEffect(Unit) { while (true) { on = Pixal3DJob.running; kotlinx.coroutines.delay(1000) } }
    DisposableEffect(on) {
        if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

object BuildPrep {
    /** Set when the "3D avatar is ready" notification is tapped; the 3D page then shows the offer first. */
    @Volatile var justBuilt: String? = null

    /**
     * Asks ONCE (ever) to exempt the app from battery optimisation before the first 3D build. Declining is fine: the
     * foreground service and the wake lock keep a build running; the exemption only makes Android less likely to pause it.
     */
    fun askBatteryOnce(act: MainActivity) {
        val sp = act.getSharedPreferences("belle_global", Context.MODE_PRIVATE)
        val pm = act.getSystemService(android.os.PowerManager::class.java)
        if (sp.getBoolean("battery_prompted", false) || pm.isIgnoringBatteryOptimizations(act.packageName)) return
        sp.edit().putBoolean("battery_prompted", true).apply()
        runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }
            .onFailure { runCatching { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Make [profileId] the active assistant and switch every widget to its 3D model (if it has one). */
    fun showOnWidget(ctx: Context, profileId: String) {
        ProfileStore.setActive(ctx, profileId)
        if (app.belle.assistant.avatar.Avatar3DFiles.has(ctx, profileId)) {
            val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, AssistantWidget::class.java))
            val e = ctx.getSharedPreferences("belle_widgets", Context.MODE_PRIVATE).edit(); ids.forEach { e.putString("mode_$it", "3d") }; e.apply()
        }
        AssistantWidget.avatarChanged(ctx)
    }
}

/** The diagnostics export: a zip with the build history, the build's events, last_run.json and filtered app events. */
object DiagnosticsExport {
    fun header(ctx: Context): List<String> {
        val mi = android.app.ActivityManager.MemoryInfo(); ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return listOf(buildLabel(), "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
            "RAM ${"%.1f".format(mi.totalMem / 1073741824.0)} GB", "Made: ${BuildRecord.clock(System.currentTimeMillis())}",
            "Contains: 3D build history and events, the last run's measurements, and app events about 3D, installs, downloads and errors. No speech, replies, memory or pictures.")
    }
    fun text(ctx: Context): String {
        val h = Pixal3DJob.history(ctx); Pixal3DJob.settleHistory(ctx)
        val appEvents = synchronized(app.belle.assistant.service.AssistantRuntime.log) { app.belle.assistant.service.AssistantRuntime.log.toList() }
        return BuildLog.report(header(ctx), h.all(), Pixal3DJob.pendingSummary(ctx), h.events(),
            runCatching { Pixal3DJob.lastRun(ctx).readText() }.getOrNull(), appEvents)
    }
    fun zip(ctx: Context): File {
        val d = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(d, "milkymoo-3d-log-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray()); z.closeEntry() }
            put("build-log.txt", text(ctx))
            put("history.json", org.json.JSONArray().apply { Pixal3DJob.history(ctx).all().forEach { put(it.json()) } }.toString(1))
            runCatching { Pixal3DJob.lastRun(ctx).readText() }.getOrNull()?.let { put("last_run.json", it) }
        }
        return f
    }
    fun share(act: MainActivity) = runCatching {
        val f = zip(act)
        val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.exports", f)
        act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Milky Moo's Assistant 3D build log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the build log"))
    }.onFailure { act.toast("Couldn't share the log: ${it.message}") }
}

/** Export log: Share… (share sheet) and Save… (pick a place with the system file picker). */
@Composable
fun ExportLogButtons(act: MainActivity) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread { val r = runCatching { val f = DiagnosticsExport.zip(act); act.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }
            act.runOnUiThread { act.toast(if (r.isSuccess) "Log saved" else "Couldn't save the log: ${r.exceptionOrNull()?.message}") } }.start()
    }
    ButtonRow {
        OutlinedButton(onClick = { DiagnosticsExport.share(act) }) { BtnText("Export log: share…") }
        OutlinedButton(onClick = { save.launch("milkymoo-3d-log.zip") }) { BtnText("Save…") }
    }
    Help("A zip with the 3D build history and events, the last run's measurements and app events about 3D, installs, downloads and errors. No speech, replies, memory or pictures.")
}

/**
 * The 3D build log (3D page and Avatar Studio): an orphan model to attach, the unfinished build with Resume, the newest
 * result with "make active / show on widget", the history (start → end, for whom, stage, result or error, duration,
 * peak RAM) and Export log.
 */
@Composable
fun BuildLogPanel(s: UiState, act: MainActivity, maxRuns: Int = 8) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); tick++ } }
    tick; s.rev
    val hist = remember { Pixal3DJob.history(act) }
    val running = Pixal3DJob.running
    remember(tick / 4) { Pixal3DJob.settleHistory(act) }
    val runs = remember(tick, s.rev) { hist.all() }

    // 1. a finished model nobody got
    val orphan = remember(tick / 4, s.rev) { Pixal3DJob.orphan(act) }
    if (orphan) {
        var who by remember { mutableStateOf<String?>(null) }; var working by remember { mutableStateOf(false) }
        Text("A finished 3D model wasn't given to any assistant", style = MaterialTheme.typography.titleMedium)
        Help("Attach last generated model to…")
        AssistantChoice(act, who, !working) { who = it }
        Button(enabled = who != null && !working, onClick = { val id = who ?: return@Button; working = true
            Thread { val r = runCatching { Pixal3DJob.attachOrphan(act, id) }
                act.runOnUiThread { working = false; s.rev++; act.toast(r.fold({ "Attached to ${ProfileStore.name(act, id)}" }, { "Couldn't attach it: ${it.message}" })) } }.start()
        }) { BtnText(if (working) "Attaching…" else "Attach") }
    }

    // 2. the unfinished build
    Pixal3DJob.pendingSummary(act)?.takeIf { !running }?.let { sum ->
        Text("Unfinished build", style = MaterialTheme.typography.titleMedium)
        Text(sum, style = MaterialTheme.typography.bodyMedium)
        ButtonRow {
            Button(enabled = Pixal3DJob.canResume(act), onClick = { BuildPrep.askBatteryOnce(act); Pixal3DService.resumeIfPending(act); s.rev++ }) { BtnText("Resume") }
            TextButton(onClick = { runs.lastOrNull { it.result != BuildRecord.DONE }?.let { hist.finish(it.id, BuildRecord.CANCELLED, System.currentTimeMillis(), "discarded") }
                Pixal3DJob.discardJob(act); s.rev++ }) { BtnText("Discard") }
        }
        Pixal3DJob.resumeBlocked(act)?.let { why -> Help("It won't resume by itself: $why. Your saved progress is kept; pick the picture again to start fresh, or export the log to see why.") }
    }

    // 3. the newest result: offer to make that assistant active and show it on the widget
    runs.lastOrNull()?.takeIf { it.result == BuildRecord.DONE }?.let { r ->
        val active = ProfileStore.activeId(act) == r.profileId
        if (r.profileId in ProfileStore.ids(act) && !active) Column {
            Text("${r.profileName}'s 3D avatar is ready.", style = MaterialTheme.typography.bodyLarge)
            Button(modifier = Modifier.fillMaxWidth(), onClick = { BuildPrep.showOnWidget(act, r.profileId); s.rev++; act.toast("${r.profileName} is active and shown in 3D on the widget") }) { BtnText("Make active") }
            Help("Makes ${r.profileName} the active assistant and shows the 3D model on the widget.")
        }
    }

    // 4. history
    Text("Build history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (runs.isEmpty()) Help("No 3D builds yet. Each build is listed here with its start and end time, the assistant, how far it got, the result or error, the time and the peak memory.")
    else runs.asReversed().take(maxRuns).forEach { r ->
        val c = when (r.result) { BuildRecord.DONE -> MaterialTheme.colorScheme.primary; BuildRecord.FAILED, BuildRecord.INTERRUPTED, BuildRecord.OUT_OF_MEMORY -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant }
        Text("• " + r.line(), style = MaterialTheme.typography.bodyMedium, color = c, modifier = Modifier.padding(vertical = 2.dp))
    }
    ExportLogButtons(act)
}
