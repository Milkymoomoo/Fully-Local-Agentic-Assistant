package app.belle.assistant.ui

import android.content.Context

/**
 * Desktop twin of the Android home-screen widget's refresh hooks (same name and calls, so ProfileStore and the avatar
 * code compile unchanged). On the desktop the "widget" is the desktop widget window; these tell it to redraw for the
 * active assistant (and drop any cached frames of the previous one).
 */
object AssistantWidget {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun avatarChanged(ctx: Context) { listeners.forEach { runCatching(it) } }
    fun updateAll(ctx: Context) { listeners.forEach { runCatching(it) } }
    fun onStateChanged(ctx: Context) { listeners.forEach { runCatching(it) } }
    /** "Moving picture" for the desktop widget (off = a still picture, also while talking). */
    fun animated(ctx: Context): Boolean = ctx.getSharedPreferences("belle_widgets", Context.MODE_PRIVATE).getBoolean("desktop_animated", true)
    fun setAnimated(ctx: Context, on: Boolean) { ctx.getSharedPreferences("belle_widgets", Context.MODE_PRIVATE).edit().putBoolean("desktop_animated", on).apply(); updateAll(ctx) }
    /** Crops fully transparent rows and columns (the name art is drawn into a wider bitmap than it needs). */
    fun trim(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        if (x1 < 0 || (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1)) return b
        return android.graphics.Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
