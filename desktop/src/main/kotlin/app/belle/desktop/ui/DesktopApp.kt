package app.belle.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.application
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import app.belle.assistant.ui.MainActivity
import app.belle.desktop.DesktopContext
import app.belle.desktop.DesktopPrefs
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * The Windows program's windows: the desktop widget (always composed, so the program keeps running while it's hidden
 * in the tray), the settings window (Home, Conversation, Settings — the phone app's screens) and the framing / rig
 * editors; plus the tray icon, which keeps working with every window closed: wake word, listening, voice and 3D
 * builds all run in the background.
 */
object DesktopApp {
    val widgetShown = mutableStateOf(DesktopPrefs.widgetVisible)
    val onTop = mutableStateOf(DesktopPrefs.widgetOnTop)
    val settingsOpen = mutableStateOf(false)
    /** Bumped to bring the settings window to the front when it is already open. */
    val settingsFront = mutableStateOf(0)
    /** Open editor windows: "framing:<surface>" / "rig". */
    val editors = androidx.compose.runtime.mutableStateListOf<String>()
    /** Used by the window icons and the tray. */
    val appIcon by lazy { javaClass.getResourceAsStream("/icons/app.png")?.use { ImageIO.read(it) } }
    val iconPainter by lazy { appIcon?.let { BitmapPainter(it.toComposeImageBitmap()) } }
    @Volatile private var quit: () -> Unit = {}

    fun showSettings(tab: String? = null, page: String? = null) = UiThread.post {
        val s = MainActivity.instance.state
        if (page != null) { s.tab = "settings"; s.page = page } else if (tab != null) { s.tab = tab; if (tab == "settings") s.page = "" }
        settingsOpen.value = true; settingsFront.value++
    }
    fun setWidget(shown: Boolean) = UiThread.post { widgetShown.value = shown; DesktopPrefs.widgetVisible = shown; Tray.refresh() }
    fun setOnTop(on: Boolean) = UiThread.post { onTop.value = on; DesktopPrefs.widgetOnTop = on; Tray.refresh() }
    /** Bumped by "Put it back in the corner": the widget window takes its default place and size again. */
    val placeRev = mutableStateOf(0)
    fun resetWidgetPlace() = UiThread.post { DesktopPrefs.widgetX = -1; DesktopPrefs.widgetY = -1; DesktopPrefs.widgetW = 260; DesktopPrefs.widgetH = 340; placeRev.value++; setWidget(true) }
    fun openEditor(id: String) = UiThread.post { if (id !in editors) editors += id }
    fun exit() = UiThread.post { quit() }

    /** Talk: start a conversation, or end the one going on (the widget's and the tray's button). */
    fun talk() {
        val ctx = DesktopContext
        if (AssistantRuntime.inSession) AssistantRuntime.endSession(ctx, "widget") else AssistantService.start(ctx, AssistantService.ACTION_LISTEN, from = "widget")
    }

    fun run(openSettings: Boolean) {
        if (openSettings) { settingsOpen.value = true; DesktopPrefs.welcomed = true }
        Notify.onOpenPage = { page, prof -> if (prof != null) app.belle.assistant.ui.BuildPrep.justBuilt = prof; showSettings(page = page) }
        SingleInstance.onShow = { setWidget(true); showSettings() }
        UiThread.post { Tray.install() }
        application(exitProcessOnExit = true) {
            quit = { Tray.remove(); AssistantService.stop(); exitApplication() }
            Provide {
                WidgetWindow(visible = widgetShown.value, onTop = onTop.value)
                if (settingsOpen.value) SettingsWindow(front = settingsFront.value, onClose = { settingsOpen.value = false })
                for (id in editors.toList()) EditorWindow(id, onClose = { editors.remove(id) })
            }
        }
    }

    @Composable private fun Provide(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalContext provides MainActivity.instance, content = content)
}

/** The tray icon and its menu (AWT's, so a click on a Windows notification can open the page it's about). */
object Tray {
    private var icon: TrayIcon? = null
    val present get() = icon != null
    private val showHide = MenuItem(); private val pin = MenuItem(); private val talk = MenuItem("Talk")
    private val read = MenuItem("Read to me"); private val mode = MenuItem()

    fun install() {
        if (!SystemTray.isSupported()) { android.util.Log.w("Tray", "no system tray on this desktop"); return }
        val img = DesktopApp.appIcon ?: return
        val menu = PopupMenu()
        talk.addActionListener { DesktopApp.talk() }
        read.addActionListener { if (runCatching { AssistantRuntime.bookNow() }.getOrNull() == null) DesktopApp.showSettings(page = "books") else Thread { runCatching { AssistantRuntime.bookControl(DesktopContext, "toggle") } }.start() }
        showHide.addActionListener { DesktopApp.setWidget(!DesktopApp.widgetShown.value) }
        pin.addActionListener { DesktopApp.setOnTop(!DesktopApp.onTop.value) }
        mode.addActionListener { WidgetMode.toggle(DesktopContext) }
        val settings = MenuItem("Settings…").apply { addActionListener { DesktopApp.showSettings(tab = "settings") } }
        val studio = MenuItem("Avatar Studio…").apply { addActionListener { DesktopApp.showSettings(page = "studio") } }
        val conv = MenuItem("Conversation…").apply { addActionListener { DesktopApp.showSettings(tab = "chat") } }
        val quit = MenuItem("Quit").apply { addActionListener { DesktopApp.exit() } }
        listOf(talk, read, null, showHide, pin, mode, null, conv, studio, settings, null, quit).forEach { if (it == null) menu.addSeparator() else menu.add(it) }
        val t = TrayIcon(img, "Milky Moo's Assistant", menu).apply {
            isImageAutoSize = true
            addActionListener { if (!Notify.clicked()) DesktopApp.setWidget(true) }   // double-click / notification click
        }
        runCatching { SystemTray.getSystemTray().add(t) }.onFailure { android.util.Log.w("Tray", "couldn't add the tray icon", it); return }
        icon = t; Notify.tray = t
        refresh()
        AssistantRuntime.addListener { UiThread.post { refresh() } }
    }
    fun refresh() {
        showHide.label = if (DesktopApp.widgetShown.value) "Hide widget" else "Show widget"
        pin.label = if (DesktopApp.onTop.value) "Unpin widget (not always on top)" else "Pin widget on top"
        talk.label = if (AssistantRuntime.inSession) "End conversation" else "Talk"
        mode.label = WidgetMode.label(DesktopContext)
        mode.isEnabled = WidgetMode.has3d(DesktopContext)
        val book = runCatching { AssistantRuntime.bookNow() }.getOrNull()
        read.label = when { book == null -> "Read to me…"; book.second -> "Pause the book"; else -> "Play the book" }
        if (Notify.progressText.value == null) icon?.toolTip = "Milky Moo's Assistant — ${app.belle.assistant.ui.stateLabel(AssistantRuntime.ui, AssistantRuntime.mode)}".take(127)
    }
    fun remove() { icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }; icon = null; Notify.tray = null }
}
