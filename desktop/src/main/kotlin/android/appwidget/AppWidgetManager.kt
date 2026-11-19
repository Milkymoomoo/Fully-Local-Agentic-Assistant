package android.appwidget

/**
 * Desktop stand-in: the PC has one "widget", the desktop widget window, with id [DESKTOP_ID]; its 2D/3D choice is
 * stored like a phone widget's ("mode_1" in belle_widgets), so the shared "show it on the widget" code works.
 */
class AppWidgetManager private constructor() {
    fun getAppWidgetIds(c: android.content.ComponentName): IntArray = intArrayOf(DESKTOP_ID)
    val isRequestPinAppWidgetSupported = false
    fun requestPinAppWidget(c: android.content.ComponentName, extras: android.os.Bundle?, cb: Any?) = false
    companion object { const val DESKTOP_ID = 1; private val one = AppWidgetManager(); @JvmStatic fun getInstance(ctx: android.content.Context) = one }
}
