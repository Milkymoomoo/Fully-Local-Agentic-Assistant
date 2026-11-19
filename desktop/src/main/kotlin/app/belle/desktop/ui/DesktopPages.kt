package app.belle.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.belle.assistant.core.Eta
import app.belle.assistant.core.TransferEta
import app.belle.assistant.threed.ModelInstaller
import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.BtnText
import app.belle.assistant.ui.ExtraPage
import app.belle.assistant.ui.Help
import app.belle.assistant.ui.MainActivity
import app.belle.assistant.ui.NameStyleSection
import app.belle.assistant.ui.NavRow
import app.belle.assistant.ui.PlatformPages
import app.belle.assistant.ui.Section
import app.belle.assistant.ui.SwitchRow
import app.belle.assistant.ui.UiState
import app.belle.assistant.ui.fmtMb
import app.belle.desktop.DesktopContext
import app.belle.desktop.DesktopPrefs
import app.belle.desktop.Natives
import app.belle.desktop.RuntimePacks
import app.belle.desktop.accel.Backend
import app.belle.desktop.accel.BackendChoice
import app.belle.desktop.accel.HardwareProbe
import app.belle.desktop.platform.Autostart
import app.belle.desktop.platform.WinActions

/** The Windows program's own settings pages, added to the shared settings list, and its line in Simple settings. */
object DesktopPages {
    fun register() {
        PlatformPages.extra = listOf(
            ExtraPage("widgetwin", "🪟", "Desktop widget & tray", "Avatar", { _ -> (if (DesktopApp.widgetShown.value) "Shown" else "Hidden in the tray") + if (DesktopApp.onTop.value) " · pinned on top" else "" }) { s, act -> WidgetPage(s, act) },
            ExtraPage("accel", "⚡", "Speed: CPU / GPU & downloads", "This PC", { _ -> "Photo → 3D on ${Natives.photoBackend.label} · chat on ${Natives.chatBackend.label}" }) { s, act -> AccelPage(s, act) },
        )
        PlatformPages.simple = { s, _ ->
            Section("This PC") {
                SwitchRow("Keep the widget on top", "Pin the avatar widget above other windows.", DesktopApp.onTop.value) { DesktopApp.setOnTop(it) }
                NavRow("⚡", "Speed: CPU / GPU & downloads", "Photo → 3D on ${Natives.photoBackend.label} · chat on ${Natives.chatBackend.label}") { s.page = "accel" }
            }
        }
        Screens.windows[app.belle.assistant.avatar.FramingEditorActivity::class.java] = { DesktopApp.openEditor("framing") }
        Screens.windows[app.belle.assistant.avatar.RigEditorActivity::class.java] = { DesktopApp.openEditor("rig") }
        Screens.windows[MainActivity::class.java] = { i -> DesktopApp.showSettings(tab = i.getStringExtra("tab"), page = i.getStringExtra("page")) }
    }
}

@Composable
private fun WidgetPage(s: UiState, act: MainActivity) {
    s.rev
    Section("Desktop widget", "A small window with the avatar, its name and the talk button, like the phone's home-screen widget. Drag it by its top bar, resize it from its bottom-right corner.") {
        SwitchRow("Show the widget", "Hidden, it waits in the tray (the icon by the clock); the assistant keeps listening either way.", DesktopApp.widgetShown.value) { DesktopApp.setWidget(it) }
        SwitchRow("Pin on top", "Stays above other windows.", DesktopApp.onTop.value) { DesktopApp.setOnTop(it) }
        SwitchRow("Moving picture", "The avatar breathes, blinks and moves on the widget. Off = a still picture (uses less power).", AssistantWidget.animated(act)) { AssistantWidget.setAnimated(act, it); s.rev++ }
        if (WidgetMode.has3d(act)) SwitchRow("Show the 3D avatar", "The widget's own 2D / 3D switch (also the 2D/3D button on the widget).", WidgetMode.effective(act) == "3d") { WidgetMode.set(act, if (it) "3d" else "2d"); s.rev++ }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ DesktopApp.openEditor("framing") }) { BtnText("Edit the framing") }
            OutlinedButton({ DesktopApp.resetWidgetPlace() }) { BtnText("Put it back in the corner") }
        }
    }
    NameStyleSection(s, act)
    Section("Windows") {
        SwitchRow("Start with Windows", "Starts in the tray when you sign in, so the wake word works right away.", Autostart.enabled()) { Autostart.set(it); s.rev++ }
        Help("Closing the settings window or hiding the widget keeps the assistant running in the tray. To stop it completely, right-click the tray icon › Quit.")
    }
}

@Composable
private fun AccelPage(s: UiState, act: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val l: () -> Unit = { UiThread.post { tick++ } }; RuntimePacks.addListener(l); onDispose { RuntimePacks.removeListener(l) } }
    tick; s.rev
    val hw = remember { HardwareProbe.get() }
    Section("This PC") {
        Help(hw.summary())
        Help("Running now: Photo → 3D on ${Natives.photoBackend.label}, chat on ${Natives.chatBackend.label}.")
        Help("The CPU path is tested. NVIDIA CUDA is the main GPU target (its first run on a real PC is still to come). The AMD and Intel paths (DirectML, OpenVINO, Vulkan) are built in, but nobody has tried them on real hardware yet, so they may not work.")
    }
    BackendSection("Photo → 3D runs on", "Making a 3D avatar from a photo. The GPU is many times faster than the CPU; the CPU works everywhere, in plain RAM, saving its progress in chunks.",
        Backend.forPhoto3D, DesktopPrefs.photoBackend, Natives.photoBackend, photo = true) { DesktopPrefs.photoBackend = it; s.rev++ }
    BackendSection("Chat runs on", "The thinking model (llama.cpp). Speech recognition and the voice always run on the CPU.",
        Backend.forChat, DesktopPrefs.chatBackend, Natives.chatBackend, photo = false) { DesktopPrefs.chatBackend = it; s.rev++ }
    val wantP = BackendChoice.photo3D(DesktopPrefs.photoBackend, hw, RuntimePacks::photoReady).backend
    val wantC = BackendChoice.chat(DesktopPrefs.chatBackend, hw, RuntimePacks::chatReady).backend
    if (wantP != Natives.photoBackend || wantC != Natives.chatBackend) Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Restart the program to switch to ${wantP.label} (3D) and ${wantC.label} (chat).", fontWeight = FontWeight.SemiBold)
            Help("The GPU libraries are loaded once, when the program starts. A 3D build in progress carries on from its last checkpoint after the restart.")
            Button({ Restart.now() }) { BtnText("Restart now") }
        }
    }
    Section("Downloads for this PC", "Straight from their publishers (NVIDIA, Microsoft, Intel, FFmpeg), checked against a fixed SHA-256, resumable. Each part has its own Retry.") {
        val shown = RuntimePacks.ALL.filter { p -> p == RuntimePacks.FFMPEG || RuntimePacks.ready(p) || p in packsFor(DesktopPrefs.photoBackend, true) + packsFor(DesktopPrefs.chatBackend, false) ||
            (hw.nvidia && (p == RuntimePacks.CUDA || p == RuntimePacks.ORT_CUDA)) || (hw.amd && p == RuntimePacks.ORT_DML) || (hw.intelGpu && p == RuntimePacks.ORT_OPENVINO) }
        shown.forEach { PackCard(it, tick) }
        TextButton({ WinActions.openFile(app.belle.desktop.Paths.runtimes) }) { BtnText("Open the downloads folder") }
    }
    Section("While building or downloading") {
        Help("Windows is kept from going to sleep while a 3D build or a download runs (the screen can still turn off), and the build saves checkpoints so a power cut or crash resumes from the last one.")
    }
}

private fun packsFor(b: Backend, photo: Boolean) = when (b) {
    Backend.AUTO -> if (HardwareProbe.get().nvidia) RuntimePacks.needs(Backend.CUDA, photo) else emptyList()
    else -> RuntimePacks.needs(b, photo)
}

@Composable
private fun BackendSection(title: String, help: String, options: List<Backend>, chosen: Backend, running: Backend, photo: Boolean, pick: (Backend) -> Unit) {
    Section(title, help) {
        options.forEach { b ->
            val offered = !(b == Backend.ROCM && app.belle.desktop.Paths.isWindows)
            Row(Modifier.fillMaxWidth().clickable(enabled = offered) { pick(b) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                RadioButton(chosen == b, { pick(b) }, enabled = offered)
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, fontWeight = if (chosen == b) FontWeight.SemiBold else FontWeight.Normal)
                        if (!b.tested) { Spacer(Modifier.width(8.dp)); AssistChip({}, label = { Text("untested") }) }
                        if (running == b || (b == Backend.AUTO && chosen == b)) { Spacer(Modifier.width(8.dp)); Text(if (b == Backend.AUTO) "→ ${running.label}" else "in use", color = MaterialTheme.colorScheme.primary) }
                    }
                    Help(b.note)
                    if (chosen == b) packsFor(b, photo).filter { !RuntimePacks.ready(it) }.takeIf { it.isNotEmpty() }?.let { miss ->
                        Text("Needs ${miss.joinToString(" and ") { "${it.label} (${fmtMb(it.bytes)})" }} — download below.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private val packEta = HashMap<String, TransferEta>()

@Composable
private fun PackCard(p: RuntimePacks.Pack, rev: Int) {   // rev: progress ticks (the Pack itself never changes, so Compose would skip the card)
    rev
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.label, fontWeight = FontWeight.SemiBold)
            Help("${p.what} ${fmtMb(p.bytes)} download · version ${p.version}.")
            Help("Licence: ${p.license}.")
            val note = RuntimePacks.unpackNote(p)
            when {
                RuntimePacks.ready(p) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Ready", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    TextButton({ RuntimePacks.remove(p) }) { BtnText("Remove") }
                }
                note != null -> Text(note)
                else -> {
                    val inst = RuntimePacks.installer(p)
                    val busy = inst.busy
                    val done = inst.bytesDone
                    val eta = packEta.getOrPut(p.id) { TransferEta() }
                    if (busy) eta.sample(done)
                    if (busy || done > 0) {
                        LinearProgressIndicator({ (done.toFloat() / p.bytes).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Help("${fmtMb(done)} of ${fmtMb(p.bytes)}" + if (busy) " · ${eta.text(p.bytes - done)} left" else "")
                    }
                    inst.manifest.files.forEach { f ->
                        val st = inst.status[f.name]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name.substringBefore("-archive").substringBefore(".nupkg"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(when (st?.phase) {
                                ModelInstaller.Phase.DONE -> "✓"
                                ModelInstaller.Phase.DOWNLOADING -> "${(100 * st.done / maxOf(1, st.total))}%" + if (st.msg.isNotEmpty()) " · ${st.msg}" else ""
                                ModelInstaller.Phase.FAILED -> "Failed: ${st.msg}"
                                ModelInstaller.Phase.CANCELLED -> "Stopped"
                                ModelInstaller.Phase.QUEUED -> "Waiting"
                                ModelInstaller.Phase.VERIFYING, ModelInstaller.Phase.JOINING -> "Checking…"
                                else -> fmtMb(f.size)
                            }, style = MaterialTheme.typography.bodySmall)
                            if (st?.phase == ModelInstaller.Phase.FAILED || st?.phase == ModelInstaller.Phase.CANCELLED) TextButton({ RuntimePacks.retry(p, f.name) }) { BtnText("Retry") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) OutlinedButton({ RuntimePacks.cancel(p) }) { BtnText("Stop") }
                        else Button({ RuntimePacks.install(p) }) { BtnText(if (done > 0) "Continue download" else "Download (${fmtMb(p.bytes)})") }
                        TextButton({ WinActions.browse(p.licenseUrl) }) { BtnText("Licence & source") }
                    }
                }
            }
        }
    }
}
