package android.graphics.drawable

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/** Desktop stand-in for android.graphics.drawable.Drawable. */
abstract class Drawable {
    val bounds = Rect()
    var callback: Callback? = null
    open var alpha: Int = 255
    open val intrinsicWidth: Int get() = -1
    open val intrinsicHeight: Int get() = -1
    fun setBounds(l: Int, t: Int, r: Int, b: Int) = bounds.set(l, t, r, b)
    abstract fun draw(c: Canvas)
    fun invalidateSelf() { callback?.invalidateDrawable(this) }
    interface Callback { fun invalidateDrawable(who: Drawable) }
}
open class BitmapDrawable(val bitmap: Bitmap) : Drawable() {
    constructor(res: android.content.res.Resources, b: Bitmap) : this(b)
    override val intrinsicWidth get() = bitmap.width
    override val intrinsicHeight get() = bitmap.height
    override fun draw(c: Canvas) { c.drawBitmap(bitmap, null, bounds, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).also { it.alpha = alpha }) }
}

/** Animated GIF / WebP through Skia's codec: frames are decoded once and played on the real clock. */
class AnimatedImageDrawable internal constructor(private val frames: List<Bitmap>, private val durMs: IntArray) : Drawable() {
    var repeatCount = REPEAT_INFINITE
    private var started = 0L
    var isRunning = false; private set
    override val intrinsicWidth get() = frames[0].width
    override val intrinsicHeight get() = frames[0].height
    fun start() { if (!isRunning) { isRunning = true; started = System.currentTimeMillis() } }
    fun stop() { isRunning = false }
    private fun frameAt(t: Long): Int { val total = durMs.sum().coerceAtLeast(1); var x = (t % total).toInt(); for (i in durMs.indices) { if (x < durMs[i]) return i; x -= durMs[i] }; return 0 }
    override fun draw(c: Canvas) {
        val i = if (isRunning) frameAt(System.currentTimeMillis() - started) else 0
        c.drawBitmap(frames[i], null, bounds, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).also { it.alpha = alpha })
        if (isRunning) invalidateSelf()
    }
    companion object { const val REPEAT_INFINITE = -1 }
}
