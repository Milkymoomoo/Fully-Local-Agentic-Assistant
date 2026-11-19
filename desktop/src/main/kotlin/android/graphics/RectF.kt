package android.graphics

/** Desktop stand-in for android.graphics.RectF / Rect / PointF (plain value holders, as on Android). */
class RectF(@JvmField var left: Float = 0f, @JvmField var top: Float = 0f, @JvmField var right: Float = 0f, @JvmField var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(o: RectF) = set(o.left, o.top, o.right, o.bottom)
    fun inset(dx: Float, dy: Float) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun offset(dx: Float, dy: Float) { left += dx; right += dx; top += dy; bottom += dy }
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun isEmpty() = left >= right || top >= bottom
    internal fun sk() = org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom)
}
class Rect(@JvmField var left: Int = 0, @JvmField var top: Int = 0, @JvmField var right: Int = 0, @JvmField var bottom: Int = 0) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
    fun isEmpty() = left >= right || top >= bottom
}
class PointF(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f) { fun set(a: Float, b: Float) { x = a; y = b } }
