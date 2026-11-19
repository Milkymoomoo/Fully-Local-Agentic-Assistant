package android.graphics

/** Desktop stand-in for android.graphics.Path over a Skia path. */
class Path {
    enum class Direction { CW, CCW }
    enum class FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }
    var fillType: FillType = FillType.WINDING
        set(v) { field = v; sk.fillMode = when (v) { FillType.EVEN_ODD -> org.jetbrains.skia.PathFillMode.EVEN_ODD; FillType.INVERSE_WINDING -> org.jetbrains.skia.PathFillMode.INVERSE_WINDING
            FillType.INVERSE_EVEN_ODD -> org.jetbrains.skia.PathFillMode.INVERSE_EVEN_ODD; else -> org.jetbrains.skia.PathFillMode.WINDING } }
    internal var sk = org.jetbrains.skia.Path()
    fun reset() { sk = org.jetbrains.skia.Path(); fillType = fillType }
    fun rewind() = reset()
    fun moveTo(x: Float, y: Float) { sk.moveTo(x, y) }
    fun lineTo(x: Float, y: Float) { sk.lineTo(x, y) }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { sk.quadTo(x1, y1, x2, y2) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { sk.cubicTo(x1, y1, x2, y2, x3, y3) }
    fun close() { sk.closePath() }
    private fun d(d: Direction) = if (d == Direction.CW) org.jetbrains.skia.PathDirection.CLOCKWISE else org.jetbrains.skia.PathDirection.COUNTER_CLOCKWISE
    fun addCircle(x: Float, y: Float, r: Float, dir: Direction) { sk.addCircle(x, y, r, d(dir)) }
    fun addOval(r: RectF, dir: Direction) { sk.addOval(r.sk(), d(dir)) }
    fun addRect(l: Float, t: Float, r: Float, b: Float, dir: Direction) { sk.addRect(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b), d(dir)) }
    fun addRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, dir: Direction) { sk.addRRect(org.jetbrains.skia.RRect.makeLTRB(l, t, r, b, rx, ry), d(dir)) }
    fun addRect(r: RectF, dir: Direction) = addRect(r.left, r.top, r.right, r.bottom, dir)
    fun addRoundRect(r: RectF, rx: Float, ry: Float, dir: Direction) = addRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, dir)
}
