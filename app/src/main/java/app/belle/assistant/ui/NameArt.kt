package app.belle.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * Draws the assistant's name as a picture: widgets (RemoteViews) can't use custom fonts or gradient text, so the name,
 * its font and its style are rendered into a bitmap. The FULL name is always shown: the text shrinks to fit, and wraps
 * to two lines when it would get too small on one.
 */
object NameArt {
    val STYLES = listOf("plain" to "Plain", "gold" to "Metallic gold", "silver" to "Metallic silver", "rgb" to "RGB piping (animated)")
    val FONTS = listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Monospace", "casual" to "Casual", "cursive" to "Script", "condensed" to "Condensed", "black" to "Heavy")
    const val FONT_DIR = "fonts"

    fun importedFonts(ctx: Context): List<Pair<String, String>> =
        File(ctx.filesDir, FONT_DIR).listFiles()?.filter { it.extension.lowercase() in listOf("ttf", "otf") }?.sortedBy { it.name }
            ?.map { "file:${it.name}" to it.nameWithoutExtension } ?: emptyList()

    /** Copy a .ttf/.otf into the app's font folder after checking Android can load it. Returns the font id ("file:…"). */
    fun importFont(ctx: Context, displayName: String, input: java.io.InputStream): Result<String> {
        val name = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in listOf("ttf", "otf")) return Result.failure(IllegalArgumentException("Pick a .ttf or .otf font file"))
        val f = File(File(ctx.filesDir, FONT_DIR).apply { mkdirs() }, name)
        return runCatching {
            f.outputStream().use { input.copyTo(it) }
            Typeface.Builder(f).build() ?: error("not a font")
            "file:${f.name}"
        }.onFailure { f.delete() }
    }

    fun typeface(ctx: Context, font: String): Typeface = when {
        font.startsWith("file:") -> runCatching { Typeface.createFromFile(File(File(ctx.filesDir, FONT_DIR), font.removePrefix("file:"))) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        font == "serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        font == "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        font == "casual" -> Typeface.create("casual", Typeface.BOLD)
        font == "cursive" -> Typeface.create("cursive", Typeface.BOLD)
        font == "condensed" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        font == "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        else -> Typeface.DEFAULT_BOLD
    }

    /**
     * @param maxPx biggest text size; @param minPx smallest one-line size before wrapping to two lines
     * @param phase 0..1 animation phase (RGB piping cycles its colours with it)
     */
    fun render(ctx: Context, name: String, style: String, font: String, widthPx: Int, maxPx: Float, minPx: Float = maxPx * 0.62f,
               phase: Float = 0f, onDark: Boolean = true, floorPx: Float = maxPx * 0.5f, maxLines: Int = 2): Bitmap {
        val w = widthPx.coerceAtLeast(40)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = typeface(ctx, font); textAlign = Paint.Align.LEFT }
        val pad = (maxPx * 0.14f).coerceAtLeast(3f)            // room for the outline / glow
        val avail = w - 2 * pad
        val fit = fit(name, tp, avail, maxPx, minPx, floorPx, maxLines)
        val size = fit.first; tp.textSize = size; tp.textScaleX = fit.second
        val lay = layout(name, tp, avail.toInt())
        val h = (lay.height + 2 * pad).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.translate(pad, pad)
        val top = 0f; val bottom = lay.height.toFloat()
        when (style) {
            "gold", "silver" -> {
                val stops = if (style == "gold") intArrayOf(0xFFFFF4C2.toInt(), 0xFFF2C94C.toInt(), 0xFFB8860B.toInt(), 0xFFFFE08A.toInt(), 0xFF8B6508.toInt())
                            else intArrayOf(0xFFFFFFFF.toInt(), 0xFFD9DDE3.toInt(), 0xFF8E959E.toInt(), 0xFFF2F4F7.toInt(), 0xFF6B7178.toInt())
                // dark rim for contrast on any wallpaper, then the metal gradient, then a thin specular edge
                tp.style = Paint.Style.STROKE; tp.strokeWidth = size * 0.10f; tp.strokeJoin = Paint.Join.ROUND
                tp.shader = null; tp.color = if (style == "gold") 0xFF3A2A00.toInt() else 0xFF1E2226.toInt(); lay.draw(c)
                tp.style = Paint.Style.FILL
                tp.shader = LinearGradient(0f, top, 0f, bottom, stops, floatArrayOf(0f, 0.35f, 0.55f, 0.75f, 1f), Shader.TileMode.CLAMP); lay.draw(c)
                tp.style = Paint.Style.STROKE; tp.strokeWidth = size * 0.025f
                tp.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP); lay.draw(c)
                tp.shader = null; tp.style = Paint.Style.FILL
            }
            "rgb" -> {
                // animated "piping": a rainbow outline whose colours slide along the text, around a light fill
                val n = 7; val cols = IntArray(n) { i -> android.graphics.Color.HSVToColor(floatArrayOf(((i.toFloat() / (n - 1)) * 360f + phase * 360f) % 360f, 1f, 1f)) }
                val span = avail.coerceAtLeast(1f)
                tp.style = Paint.Style.STROKE; tp.strokeWidth = size * 0.16f; tp.strokeJoin = Paint.Join.ROUND
                tp.shader = LinearGradient(-span * phase, 0f, span * (1 - phase), 0f, cols, null, Shader.TileMode.MIRROR); lay.draw(c)
                tp.shader = null; tp.style = Paint.Style.FILL; tp.color = 0xFF101014.toInt(); lay.draw(c)
                tp.style = Paint.Style.STROKE; tp.strokeWidth = size * 0.035f; tp.color = 0xFFFFFFFF.toInt(); lay.draw(c)
                tp.style = Paint.Style.FILL
            }
            else -> {
                tp.color = if (onDark) 0xFFFFFFFF.toInt() else 0xFF1C1B1F.toInt()
                if (onDark) tp.setShadowLayer(size * 0.12f, 0f, size * 0.04f, 0xCC000000.toInt())
                lay.draw(c)
            }
        }
        return out
    }

    /** Lines the name took (1 or 2) at this width — used by tests and the settings preview. */
    fun lineCount(ctx: Context, name: String, font: String, widthPx: Int, maxPx: Float): Int {
        val b = render(ctx, name, "plain", font, widthPx, maxPx); val single = maxPx * 1.5f + 2 * maxPx * 0.14f
        return if (b.height > single) 2 else 1
    }

    /**
     * Text size and horizontal squeeze for the name. Order of preference, never going under [floorPx] (legibility):
     * one line down to [minPx]; then 2..[maxLines] lines, first at normal width then slightly condensed (90 %, 80 %).
     * Nothing fits → the floor size, condensed, wrapped onto as many lines as it needs (still never cut off).
     */
    fun fit(name: String, tp: TextPaint, avail: Float, maxPx: Float, minPx: Float, floorPx: Float, maxLines: Int): Pair<Float, Float> {
        fun fits(size: Float, sx: Float, lines: Int): Boolean { tp.textSize = size; tp.textScaleX = sx
            return if (lines == 1) tp.measureText(name) <= avail else layout(name, tp, avail.toInt()).let { it.lineCount <= lines && widest(it) <= avail } }
        var size = maxPx
        while (size >= minPx) { if (fits(size, 1f, 1)) return size to 1f; size -= 0.5f }
        for (lines in 2..maxOf(2, maxLines)) for (sx in floatArrayOf(1f, 0.9f, 0.8f)) {
            size = maxPx * 0.9f
            while (size >= floorPx) { if (fits(size, sx, lines)) return size to sx; size -= 0.5f }
        }
        return floorPx to 0.8f
    }

    private fun layout(t: String, tp: TextPaint, w: Int) = StaticLayout.Builder.obtain(t, 0, t.length, tp, w.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build()
    private fun widest(l: StaticLayout) = (0 until l.lineCount).maxOf { l.getLineWidth(it) }
}
