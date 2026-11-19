package app.belle.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.awt.TrayIcon

/**
 * Windows notifications (through the tray icon, so they show as normal Windows toasts and in the Action Center)
 * and the tray tooltip that stands in for the phone's ongoing progress notification.
 */
object Notify {
    /** Set by the tray when it exists. */
    @Volatile var tray: TrayIcon? = null
    /** What a click on the last notification should open (settings page, and the profile a 3D result belongs to). */
    @Volatile var lastClick: Pair<String, String?>? = null
    val progressText = mutableStateOf<String?>(null)
    @Volatile var onOpenPage: (String, String?) -> Unit = { _, _ -> }

    @Volatile private var shownAt = 0L
    fun show(title: String, text: String, error: Boolean = false, page: String? = null, p3dProfile: String? = null) {
        lastClick = page?.let { it to p3dProfile }; shownAt = System.currentTimeMillis()
        android.util.Log.i("Notify", title)
        val t = tray
        if (t != null) t.displayMessage(title, text, if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO)
        else Toasts.show("$title — $text")
    }
    fun progress(title: String?, text: String?) {
        val s = if (title == null) null else "$title\n$text"
        progressText.value = s
        tray?.toolTip = (s ?: "Milky Moo's Assistant").take(127)   // Windows caps tray tooltips at 127 characters
    }
    /** The tray icon's action: a click on a notification shown in the last 30 s opens its page (returns false otherwise). */
    fun clicked(): Boolean {
        val c = lastClick ?: return false
        if (System.currentTimeMillis() - shownAt > 30_000) return false
        lastClick = null; onOpenPage(c.first, c.second); return true
    }
}

/** Short messages at the bottom of the settings window (the phone's toasts); shown as a notification when it's closed. */
object Toasts {
    val messages = mutableStateListOf<String>()
    @Volatile var windowOpen = false
    fun show(s: String) {
        android.util.Log.i("Toast", s)
        UiThread.post {
            if (windowOpen || Notify.tray == null) { messages += s; UiThread.postDelayed(4500) { messages.remove(s) } }
            else Notify.tray?.displayMessage("Milky Moo's Assistant", s, TrayIcon.MessageType.NONE)
        }
    }
}
