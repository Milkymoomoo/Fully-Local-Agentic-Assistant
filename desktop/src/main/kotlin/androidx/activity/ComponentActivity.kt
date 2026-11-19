package androidx.activity

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Desktop stand-in for ComponentActivity: the main window. Everything a Context does goes to the one desktop
 * context; window flags map onto the PC (keep-screen-on → keep the display awake while a build log is open).
 */
abstract class ComponentActivity : Context() {
    private val base get() = app.belle.desktop.DesktopContext
    override val applicationContext: Context get() = base
    override val filesDir: File get() = base.filesDir
    override val cacheDir: File get() = base.cacheDir
    override val assets get() = base.assets
    override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(name, mode)
    override fun startActivity(i: Intent) = base.startActivity(i)
    val packageManager = android.content.pm.PackageManager
    val window = Window()
    fun runOnUiThread(r: Runnable) = app.belle.desktop.ui.UiThread.post { r.run() }
    /** The microphone service is the only service the shared screens start directly. */
    fun startService(i: Intent): android.content.ComponentName? {
        if (i.component == app.belle.assistant.service.AssistantService::class.java) app.belle.assistant.service.AssistantService.start(this, i.action ?: app.belle.assistant.service.AssistantService.ACTION_START)
        return null
    }
    fun checkSelfPermission(p: String) = android.content.pm.PackageManager.PERMISSION_GRANTED
    class Window {
        fun addFlags(f: Int) { if (f and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) app.belle.desktop.platform.KeepAwake.acquire("screen", display = true) }
        fun clearFlags(f: Int) { if (f and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) app.belle.desktop.platform.KeepAwake.release("screen") }
    }
}
