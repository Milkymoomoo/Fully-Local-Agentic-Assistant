package android.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * Desktop stand-in for android.view.View, for the app's custom-drawn views (the 2D avatar, the rig and framing
 * editors). They are hosted in Compose by the AndroidView shim, which sizes them, forwards pointer input and
 * redraws them whenever they call [invalidate] (Compose keeps the frame clock, so animation runs at the display rate).
 */
open class View(val context: Context) : Drawable.Callback {
    var width = 0; internal set
    var height = 0; internal set
    var isClickable = true
    var isFocusable = true
    var alpha = 1f
    var visibility = VISIBLE
    var tag: Any? = null
    val isShown: Boolean get() = attached && visibility == VISIBLE
    val resources: android.content.res.Resources get() = android.content.res.Resources
    /** Bumped by [invalidate]; the Compose host reads it while drawing, so a change schedules a redraw. */
    val frames = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile internal var attached = false
    private var touch: OnTouchListener? = null
    private var click: OnClickListener? = null

    open fun onDraw(c: Canvas) {}
    open fun draw(c: Canvas) { onDraw(c) }
    open fun onAttachedToWindow() {}
    open fun onDetachedFromWindow() {}
    open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    open fun onTouchEvent(e: MotionEvent): Boolean = touch?.onTouch(this, e) ?: false
    open fun verifyDrawable(who: Drawable): Boolean = false
    override fun invalidateDrawable(who: Drawable) { invalidate() }

    fun invalidate() { java.awt.EventQueue.invokeLater { frames.intValue++ } }   // always later: often called from inside onDraw
    fun postInvalidate() = invalidate()
    fun postInvalidateOnAnimation() = invalidate()
    fun postInvalidateDelayed(ms: Long) { app.belle.desktop.ui.UiThread.postDelayed(ms) { frames.intValue++ } }
    fun post(r: Runnable): Boolean { app.belle.desktop.ui.UiThread.post { r.run() }; return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean { app.belle.desktop.ui.UiThread.postDelayed(ms) { r.run() }; return true }
    fun removeCallbacks(r: Runnable): Boolean = true
    fun setOnTouchListener(l: OnTouchListener?) { touch = l }
    fun setOnClickListener(l: OnClickListener?) { click = l }
    fun performClick(): Boolean { click?.onClick(this); return click != null }
    fun setLayerType(t: Int, p: android.graphics.Paint?) {}
    internal fun resize(w: Int, h: Int) { if (w != width || h != height) { val ow = width; val oh = height; width = w; height = h; onSizeChanged(w, h, ow, oh) } }

    fun interface OnTouchListener { fun onTouch(v: View, e: MotionEvent): Boolean }
    fun interface OnClickListener { fun onClick(v: View) }
    companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8; const val LAYER_TYPE_SOFTWARE = 1; const val LAYER_TYPE_HARDWARE = 2 }
}

/** Pointer events forwarded from Compose (one pointer: mouse or pen). */
class MotionEvent(val actionMasked: Int, val x: Float, val y: Float, val pointerCount: Int = 1) {
    val action get() = actionMasked
    fun getX(i: Int) = x
    fun getY(i: Int) = y
    companion object { const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MOVE = 2; const val ACTION_CANCEL = 3; const val ACTION_POINTER_DOWN = 5; const val ACTION_POINTER_UP = 6 }
}
