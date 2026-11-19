package app.belle.assistant.ui

import app.belle.assistant.core.Platform
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.core.AtomicFiles
import app.belle.assistant.core.StudioFlow
import app.belle.assistant.core.StudioState
import app.belle.assistant.threed.Pixal3DInstall
import app.belle.assistant.threed.Pixal3DJob
import java.io.File

/** Avatar Studio: the guided path (Simple mode). The same building blocks as the Advanced 2D / 3D avatar pages. */
object Studio {
    fun store(ctx: android.content.Context) = StudioState.store(ctx.filesDir)
    fun state(ctx: android.content.Context) = StudioState.load(store(ctx))
    fun save(ctx: android.content.Context, s: StudioState) = StudioState.save(store(ctx), s)
    fun pictureFile(ctx: android.content.Context, s: StudioState) = s.picture?.let { File(store(ctx).dir, it) }

    fun facts(ctx: android.content.Context, s: StudioState): StudioFlow.Facts {
        val pid = s.profileId
        val inst = Pixal3DInstall.get(ctx)
        val tools3d = Pixal3DJob.missing(ctx).isEmpty() && !inst.busy
        val assigned = pid != null && when (s.kind) {
            "2d" -> ProfileStore.prefsOf(ctx, pid).avatarFile.isNotEmpty() && s.finished
            "3d" -> Avatar3DFiles.mesh(ctx, pid).let { it.length() > 0 && it.lastModified() >= s.startedAt } && Pixal3DJob.pendingJob(ctx) == null && !Pixal3DJob.running
            else -> false
        }
        return StudioFlow.Facts(pid != null && pid in ProfileStore.ids(ctx), pictureFile(ctx, s)?.isFile == true,
            toolsReady = s.kind == "2d" || (s.kind == "3d" && tools3d), toolsInstalling = s.kind == "3d" && inst.busy,
            building = s.kind == "3d" && (Pixal3DJob.running || Pixal3DJob.canResume(ctx)), avatarAssigned = assigned,
            buildFailed = if (s.buildStarted && !Pixal3DJob.running) Pixal3DJob.lastError else null)
    }
    /** "Continue: step 3 of 5" for the Simple settings entry, or null when the Studio hasn't been started. */
    fun summary(ctx: android.content.Context): String? {
        val s = state(ctx); if (s.profileId == null) return null
        val st = StudioFlow.steps(s, facts(ctx, s)); val cur = StudioFlow.current(st)
        return if (st.all { it.state == StudioFlow.St.DONE }) "Finished: ${ProfileStore.name(ctx, s.profileId)} has a new ${s.kind?.uppercase()} avatar"
        else "Continue where you left off: step $cur of 5 (${st[cur - 1].title})"
    }
}

@Composable
fun AvatarStudioPage(s: UiState, act: MainActivity) {
    s.rev
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }   // progress, ETA and build state
    tick
    var st by remember { mutableStateOf(Studio.state(act)) }
    fun update(n: StudioState) { st = n; Studio.save(act, n); s.rev++ }
    val f = Studio.facts(act, st)
    // the 3D build finished (maybe after a restart): record it once
    LaunchedEffect(f.avatarAssigned) { if (st.kind == "3d" && st.buildStarted && !st.finished && f.avatarAssigned) update(st.copy(finished = true)) }
    val steps = StudioFlow.steps(st, f)
    fun state(n: Int) = when (steps[n - 1].state) { StudioFlow.St.DONE -> StepState.DONE; StudioFlow.St.NEXT -> StepState.NEXT
        StudioFlow.St.WAITING -> StepState.BLOCKED; StudioFlow.St.TODO -> StepState.TODO }
    StepsSummary(steps.map { "${it.n} ${it.title}" to state(it.n) })
    KeepScreenOnWhileBuilding(act)

    // 1 · Assistant: an existing one, or a new one by name
    StepCard(1, "Choose the assistant", "Who gets the new avatar: an assistant you have, or a new one.", state(1)) {
        val ids = ProfileStore.ids(act)
        ids.forEach { id ->
            Row(Modifier.fillMaxWidth().clickable { update(st.copy(profileId = id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = st.profileId == id, onClick = { update(st.copy(profileId = id)) })
                Text(ProfileStore.name(act, id), style = MaterialTheme.typography.bodyLarge)
            }
        }
        var name by remember { mutableStateOf("") }
        FieldRow({ m -> OutlinedTextField(name, { name = it.take(40) }, label = { Text("New assistant's name") }, singleLine = true, modifier = m) }) {
            Button(enabled = name.isNotBlank(), onClick = { val id = ProfileStore.create(act, name.trim()); name = ""; update(st.copy(profileId = id)) }) { BtnText("Create") }
        }
    }

    // 2 · Picture (kept in the Studio's own folder until it is built, so nothing is half-assigned)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = act.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            check(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) { "not a picture" }
            val name = "picture." + if (act.displayName(uri).endsWith(".gif", true)) "gif" else "png"
            AtomicFiles.write(File(Studio.store(act).dir, name), bytes)
            update(st.copy(picture = name, finished = false, buildStarted = false))
        }.onFailure { act.toast("Couldn't read that picture") }
    }
    StepCard(2, "Pick a picture", "One character, facing you. A plain or transparent background works best.", state(2), steps[1].why) {
        val pic = Studio.pictureFile(act, st)
        val bmp = remember(st.picture, pic?.lastModified()) { pic?.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }
        bmp?.let { Image(it.asImageBitmap(), "Chosen picture", Modifier.size(140.dp)) }
        Button(enabled = state(2) != StepState.BLOCKED, onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) }) {
            BtnText(if (bmp == null) "Pick a picture" else "Pick another") }
    }

    // 3 · 2D or 3D
    StepCard(3, "2D or 3D", "2D animates the picture itself, ready in seconds. 3D builds a model from it on this ${Platform.device} (minutes, 5.3 GB download once).",
        state(3), steps[2].why) {
        listOf("2d" to "2D: animate this picture", "3d" to "3D: build a 3D model from it").forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable(enabled = state(3) != StepState.BLOCKED) { update(st.copy(kind = k, finished = false, buildStarted = false)) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = st.kind == k, enabled = state(3) != StepState.BLOCKED, onClick = { update(st.copy(kind = k, finished = false, buildStarted = false)) })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    // 4 · Download all: what this choice needs (2D needs nothing extra)
    StepCard(4, "Download all", if (st.kind == "3d") "Installs 3D generation (18 files, 5.3 GB) by itself, also in the background, with time left. Wi-Fi recommended."
        else "Everything for this choice is installed.", state(4), steps[3].why) {
        when {
            st.kind == "2d" -> Text("Nothing to download: 2D avatars use only what's in the app.", style = MaterialTheme.typography.bodyMedium)
            st.kind == "3d" && Pixal3DJob.missing(act).isNotEmpty() -> Install3DPanel(act) { s.rev++ }
            st.kind == "3d" -> Text("3D generation is installed.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    // 5 · Build, then the avatar is assigned (only after the final atomic commit)
    StepCard(5, "Build the avatar", if (st.kind == "3d") "Runs on this ${Platform.device}; keeps going with the screen off, and continues after a restart." else "Gives the picture to the assistant.",
        state(5), steps[4].why) {
        val name = st.profileId?.let { ProfileStore.name(act, it) } ?: ""
        val pm = act.getSystemService(android.os.PowerManager::class.java)
        if (!Platform.desktop && st.kind == "3d" && !pm.isIgnoringBatteryOptimizations(act.packageName)) {
            Help("Optional: allow running in the background so Android doesn't pause a long build. It works without, but may be slower with the screen off.")
            TextButton(onClick = { runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }
                .onFailure { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { BtnText("Allow background running") }
        }
        val prog = Avatar3DFiles.progress
        if (st.kind == "3d" && (Pixal3DJob.running || prog != null)) {
            ProgressBlock("Building $name's 3D avatar", prog?.first ?: "Continuing…", prog?.second, Pixal3DJob.etaText,
                stopLabel = "Stop", onStop = { Pixal3DJob.cancel() }, scrollKey = "studio_build",
                stopHint = "Stop ends this build; its saved steps are discarded.")
        } else if (st.finished && f.avatarAssigned) {
            Text("Done: $name has the new ${st.kind?.uppercase()} avatar.", style = MaterialTheme.typography.bodyLarge)
            ButtonRow {
                Button(onClick = { st.profileId?.let { BuildPrep.showOnWidget(act, it) }; s.rev++; act.toast("$name is now the active assistant, also on the widget") }) { BtnText("Make $name active") }
                OutlinedButton(onClick = { update(StudioState()) }) { BtnText("Make another") }
            }
            Help("Makes $name the active assistant and shows the new avatar on the widget.")
            if (st.kind == "2d") TextButton(onClick = { st.profileId?.let { ProfileStore.setActive(act, it) }
                act.startActivity(Intent(act, app.belle.assistant.avatar.RigEditorActivity::class.java)) }) { BtnText("Check mouth & joints") }
        } else {
            val canResume = st.kind == "3d" && Pixal3DJob.canResume(act)
            Button(enabled = state(5) == StepState.NEXT || canResume, onClick = {
                val pic = Studio.pictureFile(act, st) ?: return@Button; val pid = st.profileId ?: return@Button
                if (st.kind == "2d") {
                    runCatching { assign2DPicture(act, pid, pic.readBytes(), pic.name.endsWith(".gif")) }
                        .onSuccess { ProfileStore.prefsOf(act, pid).show3d = false; update(st.copy(buildStarted = true, finished = true, startedAt = System.currentTimeMillis()))
                            app.belle.assistant.service.AssistantService.start(act, app.belle.assistant.service.AssistantService.ACTION_AVATAR); AssistantWidget.updateAll(act) }
                        .onFailure { act.toast("Couldn't use that picture: ${it.message}") }
                } else if (canResume) {
                    app.belle.assistant.threed.Pixal3DService.resumeIfPending(act)
                } else {
                    val bmp = android.graphics.BitmapFactory.decodeFile(pic.path) ?: run { act.toast("Couldn't read the picture"); return@Button }
                    BuildPrep.askBatteryOnce(act)
                    update(st.copy(buildStarted = true, finished = false, startedAt = System.currentTimeMillis()))
                    Thread { runCatching { Pixal3DJob.run(act, pid, bmp) }
                        .onFailure { e -> act.runOnUiThread { act.toast(if (e is app.belle.assistant.threed.Pixal3D.Cancelled) "Stopped" else "Couldn't build it: ${e.message}") } } }.start()
                }
            }) { BtnText(when { canResume -> "Continue building"; st.kind == "3d" -> "Build the 3D avatar"; else -> "Use this picture" }) }
        }
    }
    if (st.kind == "3d") Section("3D build log") { BuildLogPanel(s, act, maxRuns = 3) }

}
