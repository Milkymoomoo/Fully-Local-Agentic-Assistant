package app.belle.desktop.platform

import app.belle.desktop.DesktopPrefs
import app.belle.desktop.Paths

/** "Start with Windows": a per-user Run entry (HKCU, no admin rights) that starts the program in the tray. */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "MilkyMoosAssistant"
    fun available() = Paths.isWindows && System.getProperty("jpackage.app-path") != null
    fun enabled(): Boolean = DesktopPrefs.startWithWindows && available()
    fun set(on: Boolean) {
        if (!available()) { app.belle.desktop.ui.Toasts.show("Start with Windows works in the installed program."); return }
        val exe = System.getProperty("jpackage.app-path")
        val cmd = if (on) listOf("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f") else listOf("reg", "delete", KEY, "/v", NAME, "/f")
        val ok = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
        if (ok || !on) DesktopPrefs.startWithWindows = on
        if (!ok && on) app.belle.desktop.ui.Toasts.show("Couldn't add the program to Windows start-up.")
    }
}
