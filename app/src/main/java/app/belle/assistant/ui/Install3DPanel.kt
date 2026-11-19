package app.belle.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.belle.assistant.threed.ModelInstaller.Phase
import app.belle.assistant.threed.Pixal3DInstall

/** "Install 3D generation": one button, then a row per file with its progress, status and its own Retry. */
@Composable
fun Install3DPanel(act: ComponentActivity, onInstalled: () -> Unit) {
    val inst = remember { Pixal3DInstall.get(act) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++; if (inst.allDone) onInstalled() }
        Pixal3DInstall.addListener(l); onDispose { Pixal3DInstall.removeListener(l) }
    }
    var showLicense by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    if (tick < 0) return   // read tick so the panel recomposes on every installer change
    val m = inst.manifest
    val st = m.files.map { it to inst.status[it.name] }
    val failed = st.count { it.second?.phase == Phase.FAILED || it.second?.phase == Phase.CANCELLED }
    val started = st.any { (it.second?.done ?: 0) > 0 || it.second?.phase != Phase.WAITING }

    fun go() { err = Pixal3DInstall.blocker(act); if (err == null) Pixal3DInstall.installAll(act) else if (Pixal3DInstall.pendingLicenses(act).isNotEmpty()) showLicense = true }

    Column(Modifier.fillMaxWidth()) {
        // time left: bytes left / the smoothed download speed; files wait in manifest order (one at a time)
        val eta = app.belle.assistant.models.Downloads.eta(act)
        val line = st.filter { it.second?.phase == Phase.DOWNLOADING || it.second?.phase == Phase.QUEUED }
        val lineEta = eta.queue(line.map { (f, s) -> f.size - (s?.done ?: 0).coerceAtMost(f.size) })
        val etaOf = line.mapIndexed { i, (f, _) -> f.name to (i to lineEta[i]) }.toMap()
        if (inst.busy) {
            val doneFiles = st.count { it.second?.phase == Phase.DONE }
            ProgressBlock("Installing 3D generation", "Downloading ${doneFiles + 1}/${m.files.size}",
                (inst.bytesDone.toFloat() / m.totalBytes).coerceIn(0f, 1f),
                "${inst.bytesDone / 1_000_000} of ${m.totalBytes / 1_000_000} MB · ${eta.text(m.totalBytes - inst.bytesDone)}",
                stopLabel = "Cancel", onStop = { Pixal3DInstall.cancel(act) }, scrollKey = "install3d",
                stopHint = "Cancel keeps the files that have arrived; Install continues from there.", stepWord = "File")
        } else {
            ButtonRow {
                Button(onClick = { go() }) { BtnText(if (started) "Continue installing" else "Install 3D generation (${"%.1f".format(m.totalBytes / 1e9)} GB)") }
                if (failed > 0) OutlinedButton(onClick = { err = Pixal3DInstall.blocker(act); if (err == null) Pixal3DInstall.retryAll(act) }) { BtnText("Retry all ($failed)") }
            }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Help("Keeps going with the screen off, retries dropped files, resumes after a restart. Each file is SHA-256 checked. Built with DINOv3.")
        if (started) for ((f, s) in st) {
            val p = s?.phase ?: Phase.WAITING
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                // label on its own line, status (+ Retry) under it: nothing gets squeezed at large text sizes
                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                ButtonRow {
                    Text(when (p) {
                        Phase.DONE -> "✓"; Phase.WAITING -> "${"%.0f".format(f.size / 1e6)} MB"
                        Phase.QUEUED -> etaOf[f.name]?.let { (i, e) -> "${ordinal(i + 1)} in line · ${app.belle.assistant.core.Eta.format(e).removeSuffix(" left")}" } ?: "queued"
                        Phase.DOWNLOADING -> "${(s!!.done * 100 / f.size.coerceAtLeast(1))} % · ${app.belle.assistant.core.Eta.format(etaOf[f.name]?.second)}"; Phase.JOINING -> "joining"; Phase.VERIFYING -> "checking"
                        Phase.FAILED -> "failed"; Phase.CANCELLED -> "stopped"
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (p == Phase.FAILED || (p == Phase.CANCELLED && !inst.busy))
                        TextButton(onClick = { err = Pixal3DInstall.blocker(act); if (err == null) Pixal3DInstall.retry(act, f.name) }) { BtnText("Retry") }
                }
                if (p == Phase.DOWNLOADING) LinearProgressIndicator(progress = { (s!!.done.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (!s?.msg.isNullOrEmpty() && p != Phase.DONE) Text(s!!.msg, style = MaterialTheme.typography.bodySmall, color = if (p == Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    val lic = if (showLicense) Pixal3DInstall.pendingLicenses(act).firstOrNull() else null
    if (lic != null) {
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text(lic.title) },
            text = { Column { Text(lic.notice); TextButton(onClick = { runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lic.url))) } }) { BtnText("Read the full licence") } } },
            confirmButton = { Button(onClick = { Pixal3DInstall.accept(act, lic.id); showLicense = false; go() }) { BtnText("I agree — install") } },
            dismissButton = { TextButton(onClick = { showLicense = false }) { BtnText("Not now") } })
    }
}

/** 1st, 2nd, 3rd, 4th … for queue positions. */
fun ordinal(n: Int) = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
