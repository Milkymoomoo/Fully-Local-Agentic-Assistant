package app.belle.desktop

import app.belle.desktop.accel.Backend

/** Settings that only the Windows program has (window placement, tray, acceleration). Global, not per assistant. */
object DesktopPrefs {
    private val sp get() = DesktopContext.getSharedPreferences("belle_desktop", 0)
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun setInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var photoBackend: Backend
        get() = Backend.of(sp.getString("photo_backend", "auto")); set(v) = sp.edit().putString("photo_backend", v.id).apply()
    var chatBackend: Backend
        get() = Backend.of(sp.getString("chat_backend", "auto")); set(v) = sp.edit().putString("chat_backend", v.id).apply()
    /** Widget window placement in dp (-1 = not placed yet: bottom-right corner). */
    var widgetX: Int get() = int("widget_x", -1); set(v) = setInt("widget_x", v)
    var widgetY: Int get() = int("widget_y", -1); set(v) = setInt("widget_y", v)
    var widgetW: Int get() = int("widget_w", 260); set(v) = setInt("widget_w", v)
    var widgetH: Int get() = int("widget_h", 340); set(v) = setInt("widget_h", v)
    var widgetOnTop: Boolean get() = bool("widget_on_top", true); set(v) = setBool("widget_on_top", v)
    var widgetVisible: Boolean get() = bool("widget_visible", true); set(v) = setBool("widget_visible", v)
    var startWithWindows: Boolean get() = bool("start_with_windows", false); set(v) = setBool("start_with_windows", v)
    /** First start done (the settings window opens on the first start so setup is easy to find). */
    var welcomed: Boolean get() = bool("welcomed", false); set(v) = setBool("welcomed", v)
}
