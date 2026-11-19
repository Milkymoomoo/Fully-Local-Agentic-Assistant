package android.graphics

/** Desktop stand-in for android.graphics.Paint, turned into a Skia paint when drawn (Android's Canvas is Skia too). */
open class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }
    var isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
    var isFilterBitmap = flags and FILTER_BITMAP_FLAG != 0
    var isDither = false
    var color: Int = 0xFF000000.toInt()
    var alpha: Int
        get() = (color ushr 24) and 255
        set(v) { color = (color and 0x00FFFFFF) or (v.coerceIn(0, 255) shl 24) }
    var style = Style.FILL
    var strokeWidth = 0f
    var strokeCap = Cap.BUTT
    var strokeJoin = Join.MITER
    var textSize = 12f
    var textAlign = Align.LEFT
    var textScaleX = 1f
    var isFakeBoldText = false
    var typeface: Typeface? = null
    var shader: Shader? = null
    constructor(p: Paint) : this(0) { set(p) }
    fun set(p: Paint) { isAntiAlias = p.isAntiAlias; isFilterBitmap = p.isFilterBitmap; color = p.color; style = p.style; strokeWidth = p.strokeWidth
        strokeCap = p.strokeCap; strokeJoin = p.strokeJoin; textSize = p.textSize; textAlign = p.textAlign; typeface = p.typeface; shader = p.shader; isFakeBoldText = p.isFakeBoldText }
    fun setARGB(a: Int, r: Int, g: Int, b: Int) { color = (a shl 24) or (r shl 16) or (g shl 8) or b }
    fun setShadowLayer(radius: Float, dx: Float, dy: Float, c: Int) { shadow = if (radius > 0) floatArrayOf(radius, dx, dy) to c else null }
    internal var shadow: Pair<FloatArray, Int>? = null
    internal fun font(): org.jetbrains.skia.Font = org.jetbrains.skia.Font((typeface ?: Typeface.DEFAULT).sk(isFakeBoldText), textSize, textScaleX, 0f)
    fun measureText(s: String): Float = font().measureTextWidth(s)
    val fontMetrics: FontMetrics get() = font().metrics.let { FontMetrics(it.ascent, it.descent, it.top, it.bottom) }
    class FontMetrics(@JvmField val ascent: Float, @JvmField val descent: Float, @JvmField val top: Float, @JvmField val bottom: Float)
    internal fun sk(): org.jetbrains.skia.Paint = org.jetbrains.skia.Paint().also { p ->
        p.isAntiAlias = isAntiAlias; p.color = color
        p.mode = when (style) { Style.FILL -> org.jetbrains.skia.PaintMode.FILL; Style.STROKE -> org.jetbrains.skia.PaintMode.STROKE; Style.FILL_AND_STROKE -> org.jetbrains.skia.PaintMode.STROKE_AND_FILL }
        p.strokeWidth = strokeWidth
        p.strokeCap = when (strokeCap) { Cap.BUTT -> org.jetbrains.skia.PaintStrokeCap.BUTT; Cap.ROUND -> org.jetbrains.skia.PaintStrokeCap.ROUND; Cap.SQUARE -> org.jetbrains.skia.PaintStrokeCap.SQUARE }
        p.strokeJoin = when (strokeJoin) { Join.MITER -> org.jetbrains.skia.PaintStrokeJoin.MITER; Join.ROUND -> org.jetbrains.skia.PaintStrokeJoin.ROUND; Join.BEVEL -> org.jetbrains.skia.PaintStrokeJoin.BEVEL }
        shader?.let { s -> p.shader = s.sk() }
        shadow?.let { (f, c) -> p.imageFilter = org.jetbrains.skia.ImageFilter.makeDropShadow(f[1], f[2], f[0] / 2, f[0] / 2, c) }
    }
    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2; const val DITHER_FLAG = 4 }
}

/** Desktop stand-in for android.graphics.Typeface (system font families through Skia). */
class Typeface private constructor(private val family: String?, val style: Int, private val file: java.io.File? = null) {
    private val cache = HashMap<Boolean, org.jetbrains.skia.Typeface>()
    internal fun sk(fakeBold: Boolean): org.jetbrains.skia.Typeface = cache.getOrPut(fakeBold) {
        val fm = org.jetbrains.skia.FontMgr.default
        file?.let { fm.makeFromFile(it.path) } ?: run {
            val bold = style and BOLD != 0 || fakeBold; val italic = style and ITALIC != 0
            val fs = when { bold && italic -> org.jetbrains.skia.FontStyle.BOLD_ITALIC; bold -> org.jetbrains.skia.FontStyle.BOLD; italic -> org.jetbrains.skia.FontStyle.ITALIC; else -> org.jetbrains.skia.FontStyle.NORMAL }
            // the asked family, then common UI fonts (Windows, then Linux), then whatever the system calls its default
            (listOf(family) + FALLBACK).firstNotNullOfOrNull { f -> runCatching { fm.matchFamilyStyle(f, fs) }.getOrNull() }
                ?: runCatching { fm.legacyMakeTypeface("", fs) }.getOrNull()
        } ?: org.jetbrains.skia.Typeface.makeEmpty()
    }
    companion object {
        const val NORMAL = 0; const val BOLD = 1; const val ITALIC = 2; const val BOLD_ITALIC = 3
        private val FALLBACK = listOf("Segoe UI", "Arial", "DejaVu Sans", "Noto Sans", "Liberation Sans")
        @JvmField val DEFAULT = Typeface(null, NORMAL)
        @JvmField val DEFAULT_BOLD = Typeface(null, BOLD)
        @JvmField val SANS_SERIF = Typeface("Segoe UI", NORMAL)
        @JvmField val SERIF = Typeface("Georgia", NORMAL)
        @JvmField val MONOSPACE = Typeface("Consolas", NORMAL)
        @JvmStatic fun create(family: String?, style: Int) = Typeface(when (family) { "sans-serif" -> "Segoe UI"; "serif" -> "Georgia"; "monospace" -> "Consolas"; "casual" -> "Comic Sans MS"
            "cursive" -> "Segoe Script"; "sans-serif-condensed" -> "Arial Narrow"; "sans-serif-black" -> "Arial Black"; else -> family }, style)
        @JvmStatic fun create(t: Typeface?, style: Int) = Typeface(t?.family, style, t?.file)
        @JvmStatic fun createFromFile(f: java.io.File) = Typeface(null, NORMAL, f)
    }
    /** Checks the font file loads (null if Skia can't read it), as Android's Typeface.Builder does. */
    class Builder(private val f: java.io.File) {
        fun build(): Typeface? = runCatching { org.jetbrains.skia.FontMgr.default.makeFromFile(f.path) }.getOrNull()?.let { createFromFile(f) }
    }
}

/** Gradients and bitmap shaders. */
abstract class Shader { enum class TileMode { CLAMP, REPEAT, MIRROR }
    internal abstract fun sk(): org.jetbrains.skia.Shader
    internal fun mode(t: TileMode) = when (t) { TileMode.CLAMP -> org.jetbrains.skia.FilterTileMode.CLAMP; TileMode.REPEAT -> org.jetbrains.skia.FilterTileMode.REPEAT; TileMode.MIRROR -> org.jetbrains.skia.FilterTileMode.MIRROR }
}
class RadialGradient(private val cx: Float, private val cy: Float, private val r: Float, private val colors: IntArray, private val pos: FloatArray?, private val tile: TileMode) : Shader() {
    constructor(cx: Float, cy: Float, r: Float, c0: Int, c1: Int, tile: TileMode) : this(cx, cy, r, intArrayOf(c0, c1), null, tile)
    override fun sk() = org.jetbrains.skia.Shader.makeRadialGradient(cx, cy, maxOf(r, 0.001f), colors, pos, org.jetbrains.skia.GradientStyle(mode(tile), true, null))
}
class LinearGradient(private val x0: Float, private val y0: Float, private val x1: Float, private val y1: Float, private val colors: IntArray, private val pos: FloatArray?, private val tile: TileMode) : Shader() {
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, tile: TileMode) : this(x0, y0, x1, y1, intArrayOf(c0, c1), null, tile)
    override fun sk() = org.jetbrains.skia.Shader.makeLinearGradient(x0, y0, x1, y1, colors, pos, org.jetbrains.skia.GradientStyle(mode(tile), true, null))
}
