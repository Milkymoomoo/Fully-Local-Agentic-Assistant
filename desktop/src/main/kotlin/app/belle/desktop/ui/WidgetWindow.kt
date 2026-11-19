package app.belle.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.avatar.AvatarView
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.BelleTheme
import app.belle.assistant.ui.NameArt
import app.belle.assistant.ui.stateColor
import app.belle.assistant.ui.stateLabel
import app.belle.desktop.DesktopContext
import app.belle.desktop.DesktopPrefs
import java.io.File
import java.awt.Cursor

/** Redraw counter for everything the widget shows (assistant state, settings, active profile, avatar files). */
object WidgetRev { val rev = mutableIntStateOf(0); fun bump() = UiThread.post { rev.intValue++ } }

/**
 * The desktop widget: the phone widget's look (the assistant's name art, the moving avatar with its background, the
 * 2D / 3D switch, talk / end, the book button) in a small borderless window that can be pinned on top, dragged by its
 * top bar, resized from its corner and hidden to the tray. Its place and size are kept.
 */
@Composable
fun WidgetWindow(visible: Boolean, onTop: Boolean) {
    val ws = remember(DesktopApp.placeRev.value) { WindowState(position = widgetPlace(), size = DpSize(DesktopPrefs.widgetW.dp, DesktopPrefs.widgetH.dp)) }
    LaunchedEffect(ws) {
        snapshotFlow { ws.position to ws.size }.collect { (p, sz) ->
            if (p is WindowPosition.Absolute) { DesktopPrefs.widgetX = p.x.value.toInt(); DesktopPrefs.widgetY = p.y.value.toInt() }
            DesktopPrefs.widgetW = sz.width.value.toInt(); DesktopPrefs.widgetH = sz.height.value.toInt()
        }
    }
    DisposableEffect(Unit) {
        val l: () -> Unit = { WidgetRev.bump() }
        AssistantRuntime.addListener(l); AssistantWidget.listen(l)
        onDispose { AssistantRuntime.removeListener(l) }
    }
    val prefs = remember { Prefs(DesktopContext) }
    WidgetRev.rev.intValue; app.belle.assistant.SettingsRev.state.intValue
    Window(onCloseRequest = { DesktopApp.setWidget(false) }, state = ws, visible = visible, title = prefs.assistantName,
        icon = DesktopApp.iconPainter, undecorated = true, transparent = true, resizable = true, alwaysOnTop = onTop) {
        BelleTheme(prefs.theme, prefs.largeText) { WidgetContent(ws, onTop) }
    }
}

/**
 * Where the widget opens: its saved place if that is still on a screen (monitors change), otherwise the bottom-right
 * corner of the main screen's work area (above the taskbar).
 */
private fun widgetPlace(): WindowPosition {
    val w = DesktopPrefs.widgetW; val h = DesktopPrefs.widgetH
    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    if (DesktopPrefs.widgetX >= 0 || DesktopPrefs.widgetY >= 0) {
        val saved = java.awt.Rectangle(DesktopPrefs.widgetX, DesktopPrefs.widgetY, w, h)
        val onScreen = runCatching { ge.screenDevices.any { d -> val r = d.defaultConfiguration.bounds.intersection(saved); r.width >= 80 && r.height >= 60 } }.getOrDefault(false)
        if (onScreen) return WindowPosition(DesktopPrefs.widgetX.dp, DesktopPrefs.widgetY.dp)
    }
    val work = runCatching { ge.maximumWindowBounds }.getOrNull() ?: return WindowPosition.Aligned(Alignment.BottomEnd)
    return WindowPosition((work.x + work.width - w - 16).coerceAtLeast(work.x).dp, (work.y + work.height - h - 16).coerceAtLeast(work.y).dp)
}

@Composable
private fun FrameWindowScope.WidgetContent(ws: WindowState, onTop: Boolean) {
    val ctx = DesktopContext
    WidgetRev.rev.intValue; app.belle.assistant.SettingsRev.state.intValue
    val p = Prefs(ctx)
    val ui = AssistantRuntime.ui
    val inConv = AssistantRuntime.inSession
    val accent = stateColor(ui)
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(Color(0xEE15151C)).border(1.5.dp, accent.copy(alpha = if (ui == AssistantRuntime.Ui.IDLE) 0.35f else 0.9f), shape)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // top bar: drag the window by it; the name art, pin and hide
            WindowDraggableArea(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    NameArtImage(p, Modifier.weight(1f).height(30.dp))
                    MiniButton(if (onTop) "📌" else "📍", if (onTop) "Unpin (stop staying on top)" else "Pin on top", lit = onTop) { DesktopApp.setOnTop(!onTop) }
                    MiniButton("—", "Hide to the tray") { if (Tray.present) DesktopApp.setWidget(false) else window.isMinimized = true }
                }
            }
            // the avatar: click to talk (or end the conversation)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Avatar. Click to talk." }
                .pointerInput(Unit) { detectTapGestures { DesktopApp.talk() } }) {
                AvatarSurface(ui, inConv)
            }
            Text(statusLine(p, ui), style = MaterialTheme.typography.labelMedium, color = Color(0xFFE6E6EE), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                WideButton(if (inConv) "⏹" else "🎤", if (inConv) "End" else "Talk", accent) { DesktopApp.talk() }
                if (WidgetMode.has3d(ctx)) {
                    val m = WidgetMode.effective(ctx)
                    MiniButton(if (m == "3d") "3D" else "2D", if (m == "3d") "Showing the 3D avatar — click for 2D" else "Showing the 2D avatar — click for 3D", lit = m == "3d") { WidgetMode.toggle(ctx) }
                }
                AssistantRuntime.bookNow()?.let { (_, playing) ->
                    MiniButton(if (playing) "⏸" else "📖", if (playing) "Pause the book" else "Play the book", lit = playing) {
                        Thread { runCatching { AssistantRuntime.bookControl(ctx, "toggle") } }.start() }
                }
                MiniButton("⚙", "Settings") { DesktopApp.showSettings(tab = "settings") }
            }
        }
        ResizeGrip(ws, Modifier.align(Alignment.BottomEnd))
    }
}

private fun statusLine(p: Prefs, ui: AssistantRuntime.Ui): String {
    AssistantRuntime.bookNow()?.let { (line, playing) -> if (ui == AssistantRuntime.Ui.IDLE) return (if (playing) "Reading: " else "Paused: ") + line }
    if (!app.belle.assistant.service.AssistantService.inForeground) return "Listening is off — click Talk"
    app.belle.assistant.service.AssistantService.micError?.let { return "No microphone" }
    return stateLabel(ui, AssistantRuntime.mode)
}

/** The same AvatarView the phone widget draws (2D mesh animation or the 3D renderer, the background, the glow). */
@Composable
private fun AvatarSurface(ui: AssistantRuntime.Ui, inConv: Boolean) {
    val ctx = DesktopContext
    val profile = ProfileStore.currentId(ctx)
    val p = Prefs(ctx)
    val mode = WidgetMode.effective(ctx)
    val animated = AssistantWidget.animated(ctx)
    val file = p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) }
    val key = "$profile|$mode|${p.avatarFile}|${file?.lastModified()}|${Avatar3DFiles.mesh(ctx, profile).lastModified()}|${p.framing("widget").toLine()}|${app.belle.assistant.ui.FramingRev.rev}"
    androidx.compose.runtime.key(key) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { AvatarView(it, "widget", fill = true, profileId = profile).apply {
                force3d = mode == "3d"; isClickable = false; setImageFile(file) } },
            update = { v ->
                val st = if (inConv) ui else AssistantRuntime.Ui.IDLE
                if (v.state != st) v.state = st
                // "Moving picture" off: a still picture (the pose at rest), still lit by the state glow
                v.clockMs = if (animated) -1 else 0
            },
            modifier = Modifier.fillMaxSize())
    }
}

/** The assistant's name in its chosen style and font (the phone widget's name art); RGB piping cycles. */
@Composable
private fun NameArtImage(p: Prefs, modifier: Modifier) {
    val ctx = DesktopContext
    var phase by remember { mutableStateOf(0f) }
    if (p.nameStyle == "rgb") LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(120); phase = (System.currentTimeMillis() % 3000L) / 3000f } }
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val d = LocalDensity.current.density
        val wPx = (maxWidth.value * d).toInt().coerceAtLeast(40); val hPx = maxHeight.value * d
        val bmp = remember(p.assistantName, p.nameStyle, p.nameFont, wPx, hPx, phase) {
            runCatching { AssistantWidget.trim(NameArt.render(ctx, p.assistantName, p.nameStyle, p.nameFont, wPx, hPx * 0.8f, phase = phase, maxLines = 1)) }.getOrNull()
        }
        if (bmp != null) Image(bmp.asImageBitmap(), p.assistantName, Modifier.fillMaxSize(), alignment = Alignment.CenterStart,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else Text(p.assistantName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MiniButton(label: String, help: String, lit: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (lit) Color(0x5546A0FF) else Color(0x22FFFFFF), contentColor = Color.White,
        modifier = Modifier.padding(horizontal = 2.dp).size(30.dp).semantics { contentDescription = help }) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 13.sp, maxLines = 1) }
    }
}

@Composable
private fun WideButton(icon: String, label: String, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(15.dp), color = accent.copy(alpha = 0.30f), contentColor = Color.White,
        modifier = Modifier.height(30.dp).semantics { contentDescription = label }) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 13.sp); Spacer(Modifier.width(4.dp)); Text(label, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** Bottom-right corner: drag to resize (the window has no frame of its own). */
@Composable
private fun ResizeGrip(ws: WindowState, modifier: Modifier) {
    val d = LocalDensity.current.density
    Box(modifier.size(18.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR))).pointerInput(Unit) {
        detectDragGestures { ch, drag -> ch.consume()
            ws.size = DpSize((ws.size.width.value + drag.x / d).coerceIn(170f, 900f).dp, (ws.size.height.value + drag.y / d).coerceIn(200f, 1100f).dp) }
    }) { Text("◢", color = Color(0x66FFFFFF), fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp)) }
}
