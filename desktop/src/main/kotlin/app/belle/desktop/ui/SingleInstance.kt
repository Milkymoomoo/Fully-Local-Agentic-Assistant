package app.belle.desktop.ui

import app.belle.desktop.Paths
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * One copy at a time: the first copy listens on a local-only port (written to instance.port); starting the program
 * again just asks that copy to show its widget and settings, then exits.
 */
object SingleInstance {
    @Volatile var onShow: () -> Unit = {}
    private var server: ServerSocket? = null
    private val portFile get() = File(Paths.data, "instance.port")

    fun claim(): Boolean {
        val loop = InetAddress.getLoopbackAddress()
        portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let { port ->
            val told = runCatching { Socket(loop, port).use { it.soTimeout = 2000; it.getOutputStream().write("show\n".toByteArray()); it.getInputStream().read() == 'k'.code } }.getOrDefault(false)
            if (told) return false
        }
        val s = runCatching { ServerSocket(0, 4, loop) }.getOrNull() ?: return true   // no socket: run anyway
        server = s
        runCatching { Paths.data.mkdirs(); portFile.writeText(s.localPort.toString()) }
        Thread({
            while (!s.isClosed) runCatching {
                s.accept().use { c -> val line = c.getInputStream().bufferedReader().readLine()
                    if (line == "show") { c.getOutputStream().write('k'.code); onShow() } }
            }
        }, "single-instance").apply { isDaemon = true; start() }
        return true
    }
    /** Before a restart: let the new copy take over. */
    fun release() { runCatching { server?.close() }; server = null; runCatching { portFile.delete() } }
}

/** "Restart now" (after changing the GPU backend): starts a new copy of the program, then quits this one. */
object Restart {
    fun now() {
        val cmd = command() ?: run { Toasts.show("Couldn't restart by itself — please close the program from the tray and open it again."); return }
        SingleInstance.release()
        runCatching { ProcessBuilder(cmd + "--tray").inheritIO().start() }.onFailure { Toasts.show("Couldn't restart: ${it.message}"); return }
        DesktopApp.exit()
    }
    /** The installed program's own launcher when there is one (jpackage sets jpackage.app-path), else the same java command line. */
    private fun command(): List<String>? {
        System.getProperty("jpackage.app-path")?.let { return listOf(it) }
        val info = ProcessHandle.current().info()
        val exe = info.command().orElse(null) ?: return null
        return listOf(exe) + info.arguments().orElse(emptyArray()).toList()
    }
}
