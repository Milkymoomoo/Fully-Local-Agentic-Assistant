package app.belle.desktop.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.ui.AssistantWidget

/** The desktop widget's 2D / 3D choice, kept where the phone keeps a widget's ("mode_<id>" in belle_widgets). */
object WidgetMode {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("belle_widgets", Context.MODE_PRIVATE)
    private const val KEY = "mode_${AppWidgetManager.DESKTOP_ID}"
    fun has3d(ctx: Context) = Avatar3DFiles.has(ctx, ProfileStore.currentId(ctx))
    fun chosen(ctx: Context): String = sp(ctx).getString(KEY, "2d") ?: "2d"
    /** What is shown: 3D only when chosen and the active assistant has a 3D avatar. */
    fun effective(ctx: Context) = if (chosen(ctx) == "3d" && has3d(ctx)) "3d" else "2d"
    fun set(ctx: Context, mode: String) { sp(ctx).edit().putString(KEY, mode).apply(); AssistantWidget.updateAll(ctx) }
    fun toggle(ctx: Context) { if (effective(ctx) == "3d") set(ctx, "2d") else if (has3d(ctx)) set(ctx, "3d") }
    fun label(ctx: Context) = when { !has3d(ctx) -> "Widget: 2D (no 3D avatar yet)"; effective(ctx) == "3d" -> "Widget: show 2D"; else -> "Widget: show 3D" }
}
