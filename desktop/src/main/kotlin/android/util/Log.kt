package android.util

/** Desktop stand-in for android.util.Log: lines go to [app.belle.desktop.DesktopLog] (rotating file + stderr in dev). */
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = app.belle.desktop.DesktopLog.line('V', tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String): Int = app.belle.desktop.DesktopLog.line('D', tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String): Int = app.belle.desktop.DesktopLog.line('I', tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String): Int = app.belle.desktop.DesktopLog.line('W', tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?): Int = app.belle.desktop.DesktopLog.line('W', tag, msg, t)
    @JvmStatic fun e(tag: String, msg: String): Int = app.belle.desktop.DesktopLog.line('E', tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?): Int = app.belle.desktop.DesktopLog.line('E', tag, msg, t)
}
