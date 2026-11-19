package app.belle.assistant.ui

import app.belle.assistant.core.Platform
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.core.Workflow3DOverrides
import app.belle.assistant.service.AssistantService
import app.belle.assistant.threed.Pixal3D
import app.belle.assistant.threed.Pixal3DInstall
import app.belle.assistant.threed.Pixal3DJob
import app.belle.assistant.ProfileStore
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------
// Numbered steps: the same look and wording everywhere a task has an order (2D avatar, 3D avatar, Simple and Advanced).
// ---------------------------------------------------------------------------------------------------------------

enum class StepState(val label: String) { DONE("Done"), NEXT("Next"), TODO("To do"), BLOCKED("Waiting"), OPTIONAL("Optional") }

/** One numbered step: number (✓ when done), title, its state, a one-line instruction, why it's waiting, then its controls. */
@Composable
fun StepCard(n: Int, title: String, instruction: String, state: StepState, why: String? = null, content: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val accent = when (state) { StepState.DONE -> cs.primary; StepState.NEXT -> cs.tertiary; StepState.BLOCKED -> cs.outline; else -> cs.secondary }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(if (state == StepState.DONE) accent else Color.Transparent, CircleShape).border(2.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(if (state == StepState.DONE) "✓" else "$n", color = if (state == StepState.DONE) cs.onPrimary else accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                // with large text the status chip goes under the title and the body uses the full card width, so a
                // long word in the title is never broken letter by letter
                val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
                val chip = @Composable { Text(state.label, style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1, softWrap = false,
                    modifier = Modifier.border(1.dp, accent, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = if (state == StepState.BLOCKED) cs.onSurfaceVariant else cs.onSurface)
                    if (large) Box(Modifier.padding(top = 4.dp)) { chip() }
                }
                if (!large) chip()
            }
            val inset = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) 0.dp else 46.dp
            Text(instruction, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = inset))
            if (why != null) Text("Waiting: $why", style = MaterialTheme.typography.bodyMedium, color = cs.error, modifier = Modifier.padding(top = 4.dp, start = inset))
            Column(Modifier.padding(start = inset, top = 6.dp)) { content() }
        }
    }
}

/** "Done: … · Next: …" above a set of steps. */
@Composable
fun StepsSummary(steps: List<Pair<String, StepState>>) {
    val done = steps.filter { it.second == StepState.DONE }.map { it.first }
    val next = steps.firstOrNull { it.second == StepState.NEXT }?.first
    Text((if (done.isEmpty()) "Nothing done yet" else "Done: " + done.joinToString(", ")) + (if (next != null) "  ·  Next: $next" else "  ·  All set"),
        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 6.dp))
}

// ---------------------------------------------------------------------------------------------------------------
// 3D avatar: 1 Import · 2 Install 3D generation · 3 Make from a photo (Simple shows the same three steps, compact).
// ---------------------------------------------------------------------------------------------------------------

/** The three 3D steps. [full] = Advanced: adds the workflow & run settings, remove-models and the offline install. */
@Composable
fun Avatar3DSteps(s: UiState, act: MainActivity, full: Boolean) {
    s.rev
    val p = s.prefs; val pid = p.id
    var busy by remember { mutableStateOf(Avatar3DFiles.progress) }
    var eta by remember { mutableStateOf(Pixal3DJob.etaText) }
    LaunchedEffect(Unit) { while (true) { busy = Avatar3DFiles.progress; eta = Pixal3DJob.etaText; kotlinx.coroutines.delay(250) } }
    fun changed() { s.rev++; FramingRev.rev++; AssistantWidget.updateAll(act) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val files = uris.associate { act.displayName(it).lowercase() to act.contentResolver.openInputStream(it)!!.use { i -> i.readBytes() } }
        Thread {
            val r = runCatching { Avatar3DFiles.import(act, pid, files) }
            act.runOnUiThread { r.onSuccess { act.toast(it); p.show3d = true }.onFailure { act.toast("Import failed: ${it.message}") }; changed() }
        }.start()
    }
    // who the photo → 3D model is for: chosen explicitly, stored in the job, so it can never land on another assistant
    var buildFor by remember(pid) { mutableStateOf<String?>(pid) }
    KeepScreenOnWhileBuilding(act)
    val photo3d = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = runCatching { act.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            ?: run { act.toast("Couldn't read that picture"); return@rememberLauncherForActivityResult }
        val target = buildFor ?: pid
        val name = app.belle.assistant.ProfileStore.name(act, target)
        BuildPrep.askBatteryOnce(act)
        act.toast("Making $name's 3D model on this ${Platform.device}. It keeps going in the background; you'll get a notification.")
        Thread {
            val r = runCatching { Pixal3DJob.run(act, target, bmp) }
            act.runOnUiThread { r.onSuccess { act.toast(it) }.onFailure { act.toast(if (it is Pixal3D.Cancelled) "Stopped making the 3D model" else "Couldn't make the 3D model: ${it.message}") }; changed() }
        }.start()
    }
    var copying by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            val r = runCatching { Pixal3DJob.importModels(act, uri) { n, d, t -> act.runOnUiThread { copying = "Copying $n… ${d * 100 / maxOf(t, 1)}%" } } }
            act.runOnUiThread { copying = null; r.onSuccess { act.toast(it) }.onFailure { act.toast("Couldn't add the models: ${it.message}") }; changed() }
        }.start()
    }
    val has = Avatar3DFiles.has(act, pid)
    val inst = Pixal3DInstall.get(act); val total = inst.manifest.files.size
    val missing = remember(s.rev, inst.busy) { Pixal3DJob.missing(act) }
    val ready = missing.isEmpty() && !inst.busy
    val readyCount = remember(s.rev, inst.busy) { inst.readyCount() }
    val running = Pixal3DJob.running

    val st1 = if (has) StepState.DONE else StepState.NEXT
    val st2 = when { ready -> StepState.DONE; inst.busy -> StepState.NEXT; has -> StepState.OPTIONAL; else -> StepState.TODO }
    val st3 = when { !ready -> StepState.BLOCKED; running -> StepState.NEXT; has -> StepState.OPTIONAL; else -> StepState.NEXT }
    StepsSummary(listOf("1 Import" to st1, "2 Install 3D generation" to st2, "3 Make from a photo" to st3))
    // the status of a running import or build, at the top of the page (it scrolls itself there when it appears)
    if (running || busy != null) {
        val forName = Pixal3DJob.pendingProfile(act)?.let { app.belle.assistant.ProfileStore.name(act, it) }
        ProgressBlock(if (running) "Making a 3D model" + (forName?.let { " for $it" } ?: "") else if (Avatar3DFiles.builtIn) "Preparing the built-in 3D avatars" else "Importing the 3D model",
            busy?.first ?: "Continuing…", busy?.second, if (running) eta else null,
            stopLabel = if (running) "Stop" else null, onStop = if (running) ({ Pixal3DJob.cancel() }) else null, scrollKey = running,
            stopHint = "Stop ends this build; its saved steps are discarded.")
    }

    StepCard(1, "Import a 3D model you have", "Pick a .glb, .gltf, .obj or .ply (with its textures). No download needed. No model? Use steps 2 and 3.", st1) {
        if (has && full) androidx.compose.ui.viewinterop.AndroidView(
            factory = { app.belle.assistant.avatar.AvatarView(it, "home", profileId = pid).apply { setImageFile(p.avatarFile.takeIf { f -> f.isNotEmpty() }?.let { f -> File(act.filesDir, f) }) } },
            update = { v -> if (v.tag != FramingRev.rev) { v.tag = FramingRev.rev; v.refresh3d() } },
            modifier = Modifier.size(200.dp))
        ButtonRow {
            Button(enabled = busy == null, onClick = { importer.launch(arrayOf("*/*")) }) { BtnText(if (has) "Replace model" else "Import model") }
            if (has) OutlinedButton(onClick = { Avatar3DFiles.remove(act, pid); changed() }) { BtnText("Remove") }
        }
        Avatar3DFiles.info(act, pid)?.let { i ->
            Help("${"%,d".format(i.optInt("triangles"))} triangles · " + when (i.optString("rig")) { "file" -> "own skeleton (${i.optInt("bones")} bones)"; "rigid" -> "auto-rigged, N64-style parts"; else -> "auto-rigged, smooth skin" })
        }
        if (full) Help("For .gltf or .obj, pick its .bin / .mtl / texture files at the same time. Models without a skeleton are rigged automatically.")
    }

    StepCard(2, "Install 3D generation", "Downloads $total files (${"%.1f".format(inst.manifest.totalBytes / 1e9)} GB) by itself, also in the background. Wi-Fi recommended.", st2) {
        copying?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (missing.isNotEmpty()) Install3DPanel(act) { changed() }
        else {
            Text("Installed · $total of $total files ready", style = MaterialTheme.typography.bodyLarge)
            if (full) TextButton(enabled = !running, onClick = { Pixal3DJob.removeModels(act); changed() }) { BtnText("Remove 3D generation (${"%.1f".format(Pixal3DJob.modelBytes(act) / 1e9)} GB)") }
        }
        if (missing.isNotEmpty() && full) Expander("Install offline from a folder instead") {
            Help("Copy the Pixal3D model folder (the release files with manifest.json, 5.3 GB) to the ${Platform.device}, then pick it. It's copied into the app (needs 5.3 GB free); delete the folder afterwards.")
            OutlinedButton(enabled = copying == null, onClick = { folder.launch(null) }) { BtnText("Pick the model folder") }
        }
    }

    val plan = remember(s.rev) { runCatching { Pixal3DJob.plan(act) }.getOrNull() }
    StepCard(3, "Make 3D from a photo", "Pick a photo of one character. Runs on this ${Platform.device} in the background (minutes); you get a notification when it's done.", st3,
        why = if (!ready) "finish step 2 ($readyCount of $total files ready${if (inst.busy) ", installing now" else ""})" else null) {
        if (full) {
            Text("Build it for", style = MaterialTheme.typography.titleSmall)
            AssistantChoice(act, buildFor, enabled = !running) { buildFor = it }
        } else Help("Builds for ${p.assistantName} (the assistant you're editing). Avatar Studio lets you pick another.")
        Button(enabled = ready && busy == null && !running, onClick = { photo3d.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            BtnText(if (running) "Building… (status and Stop at the top)" else "Make 3D from a photo") }
        plan?.let { pl ->
            val src = if (Avatar3DFiles.workflow(act).exists()) "your workflow" else "${Platform.device} defaults"
            // the plan's minutes use this phone's measured speed after a first run; before that the desktop's, scaled to a phone
            val measured = remember(s.rev) { Pixal3DJob.device(act).measured }
            val est = app.belle.assistant.core.Eta.format(pl.estMinutes * 60 * (if (measured) 1.0 else app.belle.assistant.core.Eta.PHONE_VS_DESKTOP)).removeSuffix(" left")
            Help("Uses $src: ${pl.structure.steps} rough + ${pl.shape.steps} detailed steps · $est of sampling" + (if (measured) "" else " (first guess)") +
                (if (pl.items.any { it.adjusted }) " · fitted to this ${Platform.device}" else "") + (if (pl.items.any { it.overridden }) " · with your changes" else ""))
        }
        if (full) {
            Expander("Workflow & run settings (ComfyUI .json)") { WorkflowSection(s, act) }
            Expander("What happens, step by step") {
                listOf("Cuts the subject out (or uses the picture's own transparency)", "Measures the camera angle (MoGe-2)", "Reads the picture (DINOv3)",
                    "Rough 3D shape (structure sampler)", "Detailed shape (shape sampler)", "Builds the surface (TRELLIS.2 decoder)",
                    "Colours it from the photo", "Simplifies, rigs and saves it as this assistant's 3D avatar")
                    .forEachIndexed { n, t -> Text("${n + 1}. $t", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Advanced › 3D avatar: the three steps, then everything about showing the 3D model, all on this one page. */
@Composable
fun Avatar3DPage(s: UiState, act: MainActivity) {
    s.rev
    val p = s.prefs; val pid = p.id
    fun changed() { s.rev++; FramingRev.rev++; AssistantWidget.updateAll(act) }
    BuildPrep.justBuilt?.takeIf { it in ProfileStore.ids(act) && Avatar3DFiles.has(act, it) && it != ProfileStore.activeId(act) }?.let { id ->
        val n = ProfileStore.name(act, id)
        Section("$n's 3D avatar is ready") {
            ButtonRow {
                Button(onClick = { BuildPrep.showOnWidget(act, id); BuildPrep.justBuilt = null; s.rev++; act.toast("$n is active and shown in 3D on the widget") }) { BtnText("Make $n active") }
                TextButton(onClick = { BuildPrep.justBuilt = null; s.rev++ }) { BtnText("Not now") }
            }
            Help("Makes $n the active assistant and shows the 3D model on the widget.")
        }
    }
    Avatar3DSteps(s, act, full = true)
    Section("3D build log", "Every photo → 3D build: when, for whom, how far it got, the result or error, the time and peak memory.") { BuildLogPanel(s, act) }
    val has = Avatar3DFiles.has(act, pid)
    if (!has) { Section("Showing the 3D avatar", null) { Help("Rig, animation, framing, look and detail appear here once ${p.assistantName} has a 3D model (step 1 or 3).") }; return }
    val info = Avatar3DFiles.info(act, pid)
    Section("Show & frame") {
        SwitchRow("Use 3D on Home and the bubble", "Off = the 2D picture there. Each widget has its own 2D/3D button.", p.show3d) { s.edit { show3d = it }; changed() }
        OutlinedButton(onClick = { act.startActivity(Intent(act, app.belle.assistant.avatar.FramingEditorActivity::class.java)) }) { BtnText("Edit 3D framing") }
        Help("Camera distance, height and angle, per place (Home, bubble, widget).")
    }
    Section("Rig & animation") {
        if (info?.optString("rig") != "file") ChoiceRow("Rig", "How the body bends.", listOf("skinned" to "Smooth skin", "rigid" to "N64-style parts"), p.rigStyle3d) { st ->
            s.edit { rigStyle3d = st }
            Thread { val r = runCatching { Avatar3DFiles.import(act, pid, mapOf("avatar.glb" to Avatar3DFiles.mesh(act, pid).readBytes()), st) }
                act.runOnUiThread { r.onFailure { act.toast("Re-rig failed: ${it.message}") }; changed() } }.start()
        } else Help("This model has its own skeleton (${info.optInt("bones")} bones).")
        val cur = when { p.animStrength >= 0.75f -> 1f; p.animStrength >= 0.25f -> 0.5f; else -> 0f }
        ChoiceRow("Animation strength", when (cur) { 1f -> "Every part moves fully; parts that touch may stretch."
            0.5f -> "Half the motion where parts touch; little stretching." else -> "No stretching; touching parts barely move." },
            listOf(1f to "Full", 0.5f to "Balanced", 0f to "No stretching"), cur) { v -> s.edit { animStrength = v }; FramingRev.rev++; changed() }
        SwitchRow("Body animation", "Breathing, head moves and gestures (2D and 3D). Off saves battery.", p.avatarAnimate) { s.edit { avatarAnimate = it }; changed() }
    }
    Section("Look", "One style for Home, the bubble and the widget.") {
        app.belle.assistant.core.Shader3D.entries.forEach { sh ->
            Row(Modifier.fillMaxWidth().clickable { s.edit { shader3d = sh }; changed() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.shader3d == sh, onClick = { s.edit { shader3d = sh }; changed() })
                Column { Text(sh.label, style = MaterialTheme.typography.titleMedium)
                    Text(when (sh) { app.belle.assistant.core.Shader3D.NONE -> "Smooth, modern lighting."
                        app.belle.assistant.core.Shader3D.RETROSTATION -> "PS1: wobbly vertices, warped textures, 15-bit colour."
                        app.belle.assistant.core.Shader3D.RETRO64 -> "N64: soft filtered textures, vertex lighting, fog." },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (p.shader3d != app.belle.assistant.core.Shader3D.NONE) {
            SliderRow("Intensity", "${(p.shader3dIntensity * 100).toInt()}%", null, p.shader3dIntensity, 0f..1f) { v -> s.edit { shader3dIntensity = v }; FramingRev.rev++ }
            if (p.shader3d == app.belle.assistant.core.Shader3D.RETROSTATION) SwitchRow("No depth buffer", "Drawn back-to-front like a PS1 (more wobble, a bit more CPU).", p.psxNoDepth) { s.edit { psxNoDepth = it }; changed() }
            if (p.shader3d == app.belle.assistant.core.Shader3D.RETRO64) SwitchRow("Fog", null, p.n64Fog) { s.edit { n64Fog = it }; changed() }
        }
    }
    Section("Detail", "Lower = fewer triangles, smaller textures and fewer frames: less battery and heat.") {
        val auto = p.detail3d < 0
        SwitchRow("Automatic for this ${Platform.device}", null, auto) { s.edit { detail3d = if (it) -1f else p.detailFor("home") }; changed() }
        if (!auto) SliderRow("Detail", app.belle.assistant.core.Detail3D.label(p.detail3d), null, p.detail3d, 0f..1f) { v -> s.edit { detail3d = v }; FramingRev.rev++ }
        app.belle.assistant.core.Framing.SURFACES.forEach { (sf, label) ->
            val ov = p.detailOverride(sf)
            SwitchRow("$label: own detail", if (ov < 0) "Uses ${app.belle.assistant.core.Detail3D.label(p.detailFor(sf))}" else null, ov >= 0) { on -> p.setDetailOverride(sf, if (on) p.detailFor(sf) else -1f); changed() }
            if (ov >= 0) SliderRow("$label detail", app.belle.assistant.core.Detail3D.label(ov), null, ov, 0f..1f) { v -> p.setDetailOverride(sf, v); FramingRev.rev++ }
        }
        app.belle.assistant.avatar.Avatar3DRenderer.lastStats?.let { st ->
            Help("Last frame: ${"%,d".format(st.triangles)} triangles, ${st.renderW}×${st.renderH} px, texture mip ${st.mip}, ${"%.1f".format(st.frameMs)} ms")
        }
    }
}

/** The imported ComfyUI workflow: import / defaults, what the phone will use and why, per-value overrides and reset. */
@Composable
fun WorkflowSection(s: UiState, act: MainActivity) {
    s.rev
    val F = Avatar3DFiles
    val ov = F.overridesMap(act)
    fun save() { F.saveOverrides(act, ov); s.rev++ }
    val wfImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val txt = act.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            app.belle.assistant.core.Workflow3DSettings.parse(txt).also { F.workflow(act).writeText(txt)
                F.workflowSource(act).writeText(act.displayName(uri) + "|" + System.currentTimeMillis()) }
        }.onSuccess { act.toast("Workflow loaded: ${it.stages.size} sampling stages"); s.rev++ }
            .onFailure { act.toast("Not a TRELLIS 2 / Pixal3D ComfyUI workflow: ${it.message}") }
    }
    val src = runCatching { F.workflowSource(act).readText().split("|") }.getOrNull()?.takeIf { F.workflow(act).exists() }
    val plan = Pixal3DJob.plan(act); val base = F.baseSettings(act)
    Help("Load the .json you use in ComfyUI (TRELLIS 2 / Pixal3D, saved with Save, not Save (API)). Its steps, seeds, cfg, scheduler, sizes and background setting drive the ${Platform.device} run, fitted to this ${Platform.device}. The file isn't changed.")
    Text(if (src != null) "Using ${src[0]}" else "Using the ${Platform.device} defaults (no workflow loaded)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    ButtonRow {
        OutlinedButton(onClick = { wfImport.launch(arrayOf("application/json", "text/plain", "*/*")) }) { BtnText(if (src != null) "Load another" else "Import workflow") }
        if (src != null) TextButton(onClick = { F.workflow(act).delete(); F.workflowSource(act).delete(); s.rev++ }) { BtnText("Use ${Platform.device} defaults") }
    }
    val dev = Pixal3DJob.device(act)
    Help("Sampling ≈ ${"%.0f".format(plan.estMinutes)} min (budget ${dev.budgetMin.roundToInt()} min) · " +
        (if (dev.measured) "timed from this ${Platform.device}'s last run" else "estimated from a desktop run until this ${Platform.device} has made one") + " · ${"%.0f".format(dev.ramGb)} GB RAM")
    SubHeader("What the ${Platform.device} uses")
    plan.items.forEach { it ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row { Text(it.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.45f))
                Text(if (it.workflow.isEmpty() || it.workflow == it.used) it.used else "${it.workflow} → ${it.used}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f)) }
            it.note?.let { n -> Text(n, style = MaterialTheme.typography.bodySmall, color = when { it.overridden -> MaterialTheme.colorScheme.primary; it.adjusted -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }) }
        }
    }
    plan.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }

    SubHeader("Change a value")
    @Composable fun ResetRow(on: Boolean, reset: () -> Unit) { if (on) TextButton(onClick = reset) { BtnText("Reset to the fitted value") } }
    listOf(plan.structure, plan.shape).forEach { r ->
        val w0 = base.stage(r.name)
        Text(r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        SliderRow("Steps", "${r.steps}", null, r.steps.toFloat(), 1f..app.belle.assistant.threed.Run3DPlan.MAX_STEPS.toFloat()) { v ->
            Workflow3DOverrides.setStage(ov, r.name, "steps", v.roundToInt().toDouble()); save() }
        ResetRow(Workflow3DOverrides.has(ov, r.name, "steps")) { Workflow3DOverrides.setStage(ov, r.name, "steps", null); save() }
        SliderRow("Guidance (cfg)", "%.1f".format(r.cfg), null, r.cfg.toFloat(), 0f..15f) { v ->
            Workflow3DOverrides.setStage(ov, r.name, "cfg", ((v * 2).roundToInt() / 2.0)); save() }
        ResetRow(Workflow3DOverrides.has(ov, r.name, "cfg")) { Workflow3DOverrides.setStage(ov, r.name, "cfg", null); save() }
        ChoiceRow("Scheduler", null, listOf("normal" to "normal", "simple" to "simple"), r.scheduler) { v ->
            Workflow3DOverrides.setStage(ov, r.name, "scheduler", v.takeIf { it != w0?.scheduler }); save() }
        SwitchRow("New random seed each run", if (r.randomSeed) null else "Seed ${r.seed}", r.randomSeed) { on ->
            Workflow3DOverrides.setStage(ov, r.name, "random", on.takeIf { it != w0?.randomSeed }); save() }
        if (!r.randomSeed) {
            var seedText by remember(r.name, r.seed) { mutableStateOf(r.seed.toString()) }
            OutlinedTextField(seedText, { t -> seedText = t.filter { it.isDigit() }.take(15)
                seedText.toLongOrNull()?.let { v -> Workflow3DOverrides.setStage(ov, r.name, "seed", v.takeIf { it != w0?.seed }?.toDouble()); save() } },
                label = { Text("Seed") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    Text("Output", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    ChoiceRow("Picture size", null, listOf(512 to "512", 768 to "768", 1024 to "1024"), plan.cropSize) { v -> ov["cropSize"] = v.toDouble(); save() }
    ResetRow(ov.containsKey("cropSize")) { ov.remove("cropSize"); save() }
    ChoiceRow("Triangles", null, listOf(50_000 to "50k", 100_000 to "100k", 150_000 to "150k", 250_000 to "250k"), plan.maxTriangles) { v -> ov["decimateTarget"] = v.toDouble(); save() }
    ResetRow(ov.containsKey("decimateTarget")) { ov.remove("decimateTarget"); save() }
    ChoiceRow("Background", null, listOf(true to "Remove it", false to "Use its transparency"), plan.removeBackground) { v -> ov["removeBackground"] = v; save() }
    ResetRow(ov.containsKey("removeBackground")) { ov.remove("removeBackground"); save() }
    if (ov.isNotEmpty()) Button(onClick = { ov.clear(); save() }, modifier = Modifier.padding(top = 8.dp)) { BtnText("Reset all my changes") }
}

// ---------------------------------------------------------------------------------------------------------------
// 2D avatar: 1 Choose a picture · 2 Check mouth & joints · 3 Animation, then framing — all on one page.
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun Avatar2DSteps(s: UiState, act: MainActivity, full: Boolean) {
    val p = s.prefs
    val refresh = { AssistantService.start(act, AssistantService.ACTION_AVATAR); AssistantWidget.updateAll(act) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = act.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assign2DPicture(act, p.id, bytes, act.displayName(uri).endsWith(".gif", true)); s.rev++; refresh()
        }.onFailure { act.toast("Couldn't read that image") }
    }
    val own = p.avatarFile.isNotEmpty()
    val rigged = p.rig(p.avatarKey) != null
    val st1 = if (own) StepState.DONE else StepState.NEXT
    val st2 = if (rigged) StepState.DONE else StepState.OPTIONAL
    StepsSummary(listOf("1 Picture" to st1, "2 Mouth & joints" to st2))
    StepCard(1, "Choose a picture", "PNG, WebP or animated GIF. A transparent background looks best.", st1) {
        Text(if (own) "Your picture (${p.avatarFile.substringAfterLast('.').uppercase()})" else "Using the built-in picture", style = MaterialTheme.typography.bodyLarge)
        ButtonRow {
            Button(onClick = { pick.launch(arrayOf("image/png", "image/gif", "image/webp")) }) { BtnText(if (own) "Change picture" else "Choose picture") }
            if (own) TextButton(onClick = { s.edit { avatarFile = "" }; refresh() }) { BtnText("Use built-in") }
        }
    }
    StepCard(2, "Check the mouth & joints", "Placed automatically. If the mouth or joints look off, drag the dots; it's saved for this picture.", st2) {
        ButtonRow {
            Button(onClick = { act.startActivity(Intent(act, app.belle.assistant.avatar.RigEditorActivity::class.java)) }) { BtnText("Place mouth & joints") }
            if (rigged) OutlinedButton(onClick = { s.edit { setRig(avatarKey, null) }; refresh() }) { BtnText("Auto-place again") }
        }
    }
    StepCard(3, "Animation", "How the picture moves while it listens and talks.", StepState.OPTIONAL) {
        SwitchRow("Talking mouth", "Opens and closes in time with speech.", p.avatarMouth) { s.edit { avatarMouth = it }; refresh() }
        SwitchRow("Body animation", "Breathing, head moves and gestures. Off saves battery.", p.avatarAnimate) { s.edit { avatarAnimate = it }; refresh() }
        if (full && p.avatarFile.endsWith(".gif")) ChoiceRow("Animated GIF", null,
            listOf("play" to "Play the GIF (mouth only)", "still" to "Still frame (full body animation)"), p.avatarGifMode) { s.edit { avatarGifMode = it }; refresh() }
    }
}

/** Advanced › 2D avatar: the three steps and the 2D framing, all on one page. */
@Composable
fun Avatar2DPage(s: UiState, act: MainActivity) {
    Avatar2DSteps(s, act, full = true)
    Section("Framing", "How much of the picture shows on Home, in the bubble and on the widget: pinch to zoom, drag to move, circle / rounded / full.") {
        Button(onClick = { act.startActivity(Intent(act, app.belle.assistant.avatar.FramingEditorActivity::class.java)) }) { BtnText("Edit 2D framing") }
    }
    Section("Background", "Shared by 2D and 3D.") { NavRow("🖼", "Background", "Picture, GIF or video behind the avatar") { s.page = "background" } }
}

/**
 * Give assistant [profileId] a 2D picture: written atomically under a unique name (each assistant keeps its own picture,
 * rig and framing), then assigned; the old picture is deleted only if no other assistant uses it. Used by the 2D avatar
 * page and by Avatar Studio.
 */
fun assign2DPicture(ctx: android.content.Context, profileId: String, bytes: ByteArray, gif: Boolean): String {
    val f = File(ctx.filesDir, "avatar_${System.currentTimeMillis()}.${if (gif) "gif" else "png"}")
    app.belle.assistant.core.AtomicFiles.write(f, bytes)
    val pp = app.belle.assistant.ProfileStore.prefsOf(ctx, profileId); val old = pp.avatarFile
    pp.avatarFile = f.name
    val usedElsewhere = app.belle.assistant.ProfileStore.ids(ctx).any { app.belle.assistant.ProfileStore.prefsOf(ctx, it).avatarFile == old }
    if (old.isNotEmpty() && !usedElsewhere) File(ctx.filesDir, old).delete()
    runCatching { AssistantWidget.avatarChanged(ctx) }
    return f.name
}

// ---------------------------------------------------------------------------------------------------------------
// Where the avatar shows: floating bubble, home-screen widget, name style and listening light.
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun BubbleWidgetPage(s: UiState, act: MainActivity) {
    val p = s.prefs
    val refresh = { AssistantService.start(act, AssistantService.ACTION_AVATAR) }
    Section("Floating bubble", "A small avatar you can drag anywhere. It docks to the side. Tap = talk, long-press = menu.") {
        GatedSwitch("Show the bubble", null, p.avatarEnabled,
            if (Settings.canDrawOverlays(act)) null else "Needs \"Display over other apps\".", "Allow",
            { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))) },
            listOf("Display over other apps", "✈️ offline")) { s.edit { avatarEnabled = it }; refresh() }
        ChoiceRow("Show", null, listOf("always" to "Always", "active" to "Only while active"), p.avatarMode) { s.edit { avatarMode = it }; refresh() }
        SliderRow("Size", "${p.avatarSizeDp} dp", null, p.avatarSizeDp.toFloat(), 40f..160f) { v -> s.edit { avatarSizeDp = v.toInt() }; refresh() }
        SliderRow("Opacity", "${(p.avatarOpacity * 100).toInt()}%", null, p.avatarOpacity, 0.2f..1f) { v -> s.edit { avatarOpacity = v }; refresh() }
        SliderRow("Tuck away after", if (p.avatarAutoHideSec == 0) "Never" else fmtSec(p.avatarAutoHideSec), "When idle, it slides half off the edge and fades.",
            p.avatarAutoHideSec.toFloat(), 0f..120f) { v -> s.edit { avatarAutoHideSec = v.toInt() }; refresh() }
        OutlinedButton(onClick = { s.edit { avatarX = -1; avatarY = 400 }; refresh() }) { BtnText("Reset position") }
    }
    Section("Home-screen widget", WIDGET_HINT + " It shows the active assistant; each widget has its own 2D/3D button.") {
        var moving by remember { mutableStateOf(AssistantWidget.animated(act)) }
        SwitchRow("Moving picture", "Off = a still picture, also while talking (saves battery).", moving) { moving = it; AssistantWidget.setAnimated(act, it) }
    }
    NameStyleSection(s, act)
    Section("Listening light") {
        SwitchRow("Glow around the screen edges", "A soft pulsing glow while I'm listening.", p.edgeGlow) { s.edit { edgeGlow = it }; refresh() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            listOf(0xFF4FC3F7, 0xFFBA68C8, 0xFFF06292, 0xFF81C784, 0xFFFFB74D, 0xFFFFFFFF).map { it.toInt() }.forEach { c ->
                Box(Modifier.size(40.dp).background(Color(c), CircleShape)
                    .border(if (p.glowColor == c) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { s.edit { glowColor = c }; refresh() })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
