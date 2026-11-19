package app.belle.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.avatar.AvatarView
import app.belle.assistant.avatar.EditorStill
import app.belle.assistant.avatar.FramingCanvas
import app.belle.assistant.avatar.RigCanvas
import app.belle.assistant.core.Framing
import app.belle.assistant.service.AssistantService
import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.BelleTheme
import app.belle.assistant.ui.FramingRev
import app.belle.assistant.ui.MainActivity
import app.belle.desktop.DesktopContext
import java.io.File

/** The framing and rig editors as windows: the phone's editor canvases with desktop controls (mouse wheel zooms). */
@Composable
fun EditorWindow(id: String, onClose: () -> Unit) {
    val prefs = remember { Prefs(DesktopContext) }
    val title = if (id == "rig") "Place mouth & joints — ${prefs.assistantName}" else "Framing — ${prefs.assistantName}"
    Window(onCloseRequest = { finishEdit(); onClose() }, title = title, icon = DesktopApp.iconPainter,
        state = rememberWindowState(size = if (id == "rig") DpSize(760.dp, 860.dp) else DpSize(720.dp, 900.dp))) {
        BelleTheme(prefs.theme, prefs.largeText) {
            Surface(Modifier.fillMaxSize()) {
                if (id == "rig") RigEditor(prefs, onClose) else FramingEditor(prefs, onClose)
            }
        }
    }
}

/** The bubble / widget / Home redraw with the new framing or rig. */
private fun finishEdit() {
    FramingRev.rev++
    AssistantService.start(DesktopContext, AssistantService.ACTION_AVATAR)
    AssistantWidget.updateAll(DesktopContext)
    WidgetRev.bump()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FramingEditor(prefs: Prefs, onClose: () -> Unit) {
    val ctx = MainActivity.instance
    var surface by remember { mutableStateOf("home") }
    var rev by remember { mutableIntStateOf(0) }
    var same by remember { mutableStateOf(prefs.framingSameForAll) }
    val canvas = remember { FramingCanvas(ctx, EditorStill.load(ctx, prefs)) { f -> prefs.setFraming(surface, f); FramingRev.rev++; rev++ } }
    fun sync() { canvas.framing = prefs.framing(surface); canvas.surface = surface; canvas.invalidate(); FramingRev.rev++; rev++ }
    remember { sync(); 0 }
    rev
    val f = prefs.framing(surface)
    val has3d = Avatar3DFiles.has(ctx, ProfileStore.activeId(ctx))
    // only the Home and widget frames exist on the PC (no floating bubble)
    val surfaces = Framing.SURFACES.filter { it.first != "bubble" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Framing for ${prefs.assistantName}", style = MaterialTheme.typography.headlineSmall)
        Text("Drag to move, scroll the mouse wheel to zoom. The bright area is what shows.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(same, { on -> val cur = prefs.framing(surface); prefs.framingSameForAll = on; prefs.setFraming(surface, cur); same = on; sync() })
            Text("Use the same framing everywhere")
        }
        if (!same) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            surfaces.forEach { (sid, name) -> FilterChip(surface == sid, { surface = sid; sync() }, label = { Text(if (sid == "widget") "Desktop widget" else name) }) }
        }
        AndroidView(factory = { canvas }, modifier = Modifier.fillMaxWidth().size(width = 600.dp, height = 460.dp)
            .onPointerEvent(PointerEventType.Scroll) { e -> val d = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f; if (d != 0f) canvas.zoomBy(if (d < 0) 1.08f else 1 / 1.08f) })
        Text("Frame shape", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Framing.SHAPES.forEach { (sid, name) -> FilterChip(f.shape == sid, { prefs.setFraming(surface, prefs.framing(surface).copy(shape = sid)); sync() }, label = { Text(name) }) }
        }
        Text("3D view" + if (has3d) "" else " — make or import a 3D avatar first", style = MaterialTheme.typography.titleMedium)
        val bars = listOf(
            Triple("Camera distance", { x: Framing -> (x.dist - 1f) / 3f }) { x: Framing, v: Float -> x.copy(dist = 1f + v * 3f) },
            Triple("Camera height", { x: Framing -> (x.height + 1f) / 2f }) { x: Framing, v: Float -> x.copy(height = v * 2f - 1f) },
            Triple("Camera angle", { x: Framing -> (x.angle + 180f) / 360f }) { x: Framing, v: Float -> x.copy(angle = v * 360f - 180f) })
        bars.forEach { (name, get, set) ->
            Text(name, color = if (has3d) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(get(f).coerceIn(0f, 1f), { v -> prefs.setFraming(surface, set(prefs.framing(surface), v)); sync() }, enabled = has3d)
        }
        Text("Preview", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            surfaces.forEach { (sid, name) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.runtime.key(sid, rev) {
                        AndroidView(factory = { AvatarView(it, sid, fill = sid == "widget").apply { setImageFile(prefs.avatarFile.takeIf { a -> a.isNotEmpty() }?.let { a -> File(ctx.filesDir, a) }) } },
                            modifier = Modifier.size(170.dp))
                    }
                    Text(if (sid == "widget") "Desktop widget" else name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ prefs.setFraming(surface, prefs.framing(surface).copy(zoom = 1f, cx = .5f, cy = .5f)); sync() }) { Text("Show everything") }
            Button({ finishEdit(); onClose() }) { Text("Done") }
        }
    }
}

@Composable
private fun RigEditor(prefs: Prefs, onClose: () -> Unit) {
    val ctx = MainActivity.instance
    val bmp = remember { EditorStill.load(ctx, prefs) }
    val canvas = remember { RigCanvas(ctx, bmp, EditorStill.rig(prefs, bmp)) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Drag the dots onto the joints, and the pink box onto the mouth (its corner resizes it).", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.weight(1f).fillMaxWidth()) { AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            OutlinedButton({ canvas.rig = EditorStill.autoRig(prefs, bmp); canvas.invalidate() }) { Text("Auto-place") }
            OutlinedButton({ canvas.talkTest() }) { Text("Test mouth") }
            OutlinedButton({ canvas.rig = EditorStill.toggleExtras(canvas.rig); canvas.invalidate() }) { Text("Wings, tail, legs") }
            Button({ prefs.setRig(prefs.avatarKey, canvas.rig.copy(auto = false).toLine()); Toasts.show("Saved"); finishEdit(); onClose() }) { Text("Save") }
        }
    }
}
