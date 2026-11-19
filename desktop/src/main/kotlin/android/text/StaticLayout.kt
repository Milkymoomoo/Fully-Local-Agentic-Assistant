package android.text

import android.graphics.Canvas

open class TextPaint(flags: Int = 0) : android.graphics.Paint(flags)
abstract class Layout { enum class Alignment { ALIGN_NORMAL, ALIGN_CENTER, ALIGN_OPPOSITE }
    companion object { const val BREAK_STRATEGY_SIMPLE = 0; const val BREAK_STRATEGY_HIGH_QUALITY = 1; const val BREAK_STRATEGY_BALANCED = 2 } }

/**
 * Desktop stand-in for Android's StaticLayout: word wrapping to a width (balanced: lines as even as possible),
 * centred or left aligned, drawn line by line with the paint's font. Covers what the name art needs.
 */
class StaticLayout private constructor(private val lines: List<String>, private val paint: TextPaint, private val width: Int, private val align: Layout.Alignment) : Layout() {
    private val fm = paint.fontMetrics
    private val lineH = fm.descent - fm.ascent
    val lineCount get() = lines.size
    val height: Int get() = kotlin.math.ceil(lineH * lines.size).toInt()
    fun getLineWidth(i: Int): Float = paint.measureText(lines[i])
    fun getLineTop(i: Int): Int = (lineH * i).toInt()
    fun draw(c: Canvas) {
        val keep = paint.textAlign; paint.textAlign = android.graphics.Paint.Align.LEFT
        lines.forEachIndexed { i, l ->
            val x = when (align) { Alignment.ALIGN_CENTER -> (width - paint.measureText(l)) / 2; Alignment.ALIGN_OPPOSITE -> width - paint.measureText(l); else -> 0f }
            c.drawText(l, x, lineH * i - fm.ascent, paint)
        }
        paint.textAlign = keep
    }
    class Builder private constructor(private val text: String, private val paint: TextPaint, private val width: Int) {
        private var align = Alignment.ALIGN_NORMAL; private var balanced = false
        fun setAlignment(a: Alignment) = apply { align = a }
        fun setIncludePad(b: Boolean) = this
        fun setBreakStrategy(s: Int) = apply { balanced = s == BREAK_STRATEGY_BALANCED }
        fun setLineSpacing(add: Float, mult: Float) = this
        fun setMaxLines(n: Int) = this
        fun build(): StaticLayout {
            val words = text.split(' ').filter { it.isNotEmpty() }
            fun wrap(w: Float): List<String> { val out = ArrayList<String>(); var cur = ""
                for (wd in words) { val t = if (cur.isEmpty()) wd else "$cur $wd"; if (cur.isNotEmpty() && paint.measureText(t) > w) { out += cur; cur = wd } else cur = t }
                if (cur.isNotEmpty()) out += cur; return out.ifEmpty { listOf("") } }
            var lines = wrap(width.toFloat())
            if (balanced && lines.size > 1) {   // narrow the width while the line count stays the same: even lines
                var lo = 1f; var hi = width.toFloat()
                repeat(16) { val mid = (lo + hi) / 2; if (wrap(mid).size <= lines.size) hi = mid else lo = mid }
                lines = wrap(hi)
            }
            return StaticLayout(lines, paint, width, align)
        }
        companion object { @JvmStatic fun obtain(t: CharSequence, start: Int, end: Int, p: TextPaint, w: Int) = Builder(t.substring(start, end), p, w) }
    }
}
