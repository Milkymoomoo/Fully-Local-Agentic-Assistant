package app.belle.desktop.ui

import android.content.Context
import android.content.Intent
import java.io.File

/** What starting an Android "activity" means on the PC. */
object Screens {
    /** App screens with a desktop window (registered by the desktop UI at startup): class → opener. */
    val windows = HashMap<Class<*>, (Intent) -> Unit>()

    fun start(ctx: Context, i: Intent) {
        i.component?.let { c -> windows[c]?.invoke(i) ?: android.util.Log.w("Screens", "no desktop window for ${c.simpleName}"); return }
        val u = i.data?.toString()
        when {
            i.action == Intent.ACTION_VIEW && u != null && (u.startsWith("https://") || u.startsWith("http://")) -> app.belle.desktop.platform.WinActions.browse(u)
            i.action == Intent.ACTION_VIEW && u != null && u.startsWith("file:") -> app.belle.desktop.platform.WinActions.openFile(File(java.net.URI(u)))
            i.action == Intent.ACTION_SEND -> (i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.path?.let { app.belle.desktop.platform.WinActions.reveal(File(it)) }
            else -> android.util.Log.i("Screens", "ignored on the PC: ${i.action}")
        }
    }
}
