package android.view

import android.content.Context

/** Desktop stand-in: a mouse has no pinch, so this never scales (the desktop editors zoom with the wheel instead). */
class ScaleGestureDetector(ctx: Context, private val l: OnScaleGestureListener) {
    val isInProgress = false
    val scaleFactor = 1f
    fun onTouchEvent(e: MotionEvent) = false
    interface OnScaleGestureListener {
        fun onScale(d: ScaleGestureDetector): Boolean
        fun onScaleBegin(d: ScaleGestureDetector): Boolean
        fun onScaleEnd(d: ScaleGestureDetector)
    }
    open class SimpleOnScaleGestureListener : OnScaleGestureListener {
        override fun onScale(d: ScaleGestureDetector) = false
        override fun onScaleBegin(d: ScaleGestureDetector) = true
        override fun onScaleEnd(d: ScaleGestureDetector) {}
    }
}
