package app.belle.desktop.platform

import app.belle.desktop.DesktopLog
import app.belle.desktop.Paths

/**
 * Keeps the PC awake (no sleep, no hibernate; the screen may still turn off) while any holder is active: 3D builds and
 * downloads. A holder that asks for [display] (an open build log, like the phone's keep-screen-on) also keeps the screen on. Windows: SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED) on one dedicated thread that lives
 * as long as there is a holder (the flag belongs to the thread that set it). Linux: `systemd-inhibit` child process.
 * Released on every exit path: when the last holder releases, and by the OS when the process ends.
 */
object KeepAwake {
    private val holders = LinkedHashSet<String>()
    private val displayHolders = HashSet<String>()
    private var worker: Thread? = null
    private var inhibit: Process? = null
    @Volatile var lastNote: String = ""; private set

    private fun flags() = Win32.ES_CONTINUOUS or Win32.ES_SYSTEM_REQUIRED or (if (synchronized(this) { displayHolders.isNotEmpty() }) Win32.ES_DISPLAY_REQUIRED else 0)

    @Synchronized fun acquire(who: String, display: Boolean = false) {
        if (display) displayHolders += who
        if (!holders.add(who) || holders.size > 1) { if (display) worker?.interrupt(); return }   // interrupt: re-apply flags now
        if (Paths.isWindows) {
            worker = Thread({
                while (true) {
                    val ok = runCatching { Win32.setExecutionState(flags()) != 0 }.getOrDefault(false)
                    lastNote = if (ok) "Keeping the PC awake" else "Couldn't stop the PC from sleeping"
                    if (synchronized(this) { holders.isEmpty() }) break
                    try { Thread.sleep(30_000) } catch (_: InterruptedException) { if (synchronized(this) { holders.isEmpty() }) break }
                }
                runCatching { Win32.setExecutionState(Win32.ES_CONTINUOUS) }
            }, "keep-awake").apply { isDaemon = true; start() }
        } else {
            inhibit = runCatching { ProcessBuilder("systemd-inhibit", "--what=sleep:idle", "--who=Milky Moo's Assistant", "--why=$who", "sleep", "infinity")
                .redirectErrorStream(true).start() }.getOrNull()
            lastNote = if (inhibit != null) "Keeping the PC awake" else "Couldn't stop the PC from sleeping (no systemd-inhibit)"
        }
        DesktopLog.line('I', "KeepAwake", "on ($who): $lastNote", null)
    }

    @Synchronized fun release(who: String) {
        if (displayHolders.remove(who) && holders.size > 1) worker?.interrupt()
        if (!holders.remove(who) || holders.isNotEmpty()) return
        worker?.interrupt(); worker = null
        inhibit?.destroy(); inhibit = null
        DesktopLog.line('I', "KeepAwake", "off", null)
    }
    @Synchronized fun active(): Set<String> = holders.toSet()
}
