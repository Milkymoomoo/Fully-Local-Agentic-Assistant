package android.widget

/** Desktop stand-in: toasts show at the bottom of the settings window (or as a notification when it's closed). */
class Toast private constructor(private val s: String) {
    fun show() = app.belle.desktop.ui.Toasts.show(s)
    companion object { const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1
        @JvmStatic fun makeText(ctx: android.content.Context, s: CharSequence, len: Int) = Toast(s.toString()) }
}
