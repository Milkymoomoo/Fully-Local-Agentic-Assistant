package app.belle.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.ui.BelleTheme
import app.belle.assistant.ui.BtnText
import app.belle.assistant.ui.ChatScreen
import app.belle.assistant.ui.HomeScreen
import app.belle.assistant.ui.MainActivity
import app.belle.assistant.ui.SettingsScreen
import app.belle.assistant.ui.UiState

/**
 * Home, Conversation and Settings — the phone app's own screens (same pages, same Simple / Advanced settings, same
 * Avatar Studio), in a normal window opened from the widget's gear or the tray. Closing it leaves the assistant running.
 */
@Composable
fun SettingsWindow(front: Int, onClose: () -> Unit) {
    val act = MainActivity.instance
    val s = act.state
    val ws = rememberWindowState(size = DpSize(860.dp, 900.dp))
    Window(onCloseRequest = onClose, state = ws, title = "Milky Moo's Assistant", icon = DesktopApp.iconPainter,
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && (s.page.isNotEmpty() || s.tab != "home")) { if (s.page.isNotEmpty()) s.page = "" else s.tab = "home"; true } else false }) {
        LaunchedEffect(front) { window.isMinimized = false; window.toFront(); window.requestFocus() }
        DisposableEffect(Unit) {
            MainActivity.onScreen = true; Toasts.windowOpen = true
            val l: () -> Unit = { UiThread.post { s.tick++ } }
            AssistantRuntime.addListener(l); s.tick++; s.rev++
            onDispose { AssistantRuntime.removeListener(l); MainActivity.onScreen = false; Toasts.windowOpen = false }
        }
        s.rev
        BelleTheme(s.prefs.theme, s.prefs.largeText) { DesktopRoot(s, act) }
    }
}

@Composable
private fun DesktopRoot(s: UiState, act: MainActivity) {
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = s.tab == "home", onClick = { s.tab = "home" }, icon = { Icon(Icons.Filled.Home, null) }, label = { BtnText("Home") })
            NavigationBarItem(selected = s.tab == "chat", onClick = { s.tab = "chat" }, icon = { Icon(Icons.Filled.Face, null) }, label = { BtnText("Conversation") })
            NavigationBarItem(selected = s.tab == "settings", onClick = { s.tab = "settings"; s.page = "" }, icon = { Icon(Icons.Filled.Settings, null) }, label = { BtnText("Settings") })
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s.tab) {
                "chat" -> ChatScreen(s, act)
                "settings" -> SettingsScreen(s, act)
                else -> HomeScreen(s, act)
            }
            // the phone's toasts and "busy" line, at the bottom of the window
            Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                s.busy?.let { Toast(it) }
                Toasts.messages.forEach { Toast(it) }
            }
        }
    }
}

@Composable
private fun Toast(text: String) = Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}
