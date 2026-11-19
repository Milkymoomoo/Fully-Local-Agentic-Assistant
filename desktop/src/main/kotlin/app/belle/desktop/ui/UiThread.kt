package app.belle.desktop.ui

import java.awt.EventQueue
import javax.swing.Timer

/** The desktop UI thread (Compose for Desktop runs on the AWT event thread). */
object UiThread {
    fun post(f: () -> Unit) { if (EventQueue.isDispatchThread()) f() else EventQueue.invokeLater(f) }
    fun postDelayed(ms: Long, f: () -> Unit) { Timer(ms.toInt().coerceAtLeast(0)) { f() }.apply { isRepeats = false; start() } }
    fun isUi() = EventQueue.isDispatchThread()
}
