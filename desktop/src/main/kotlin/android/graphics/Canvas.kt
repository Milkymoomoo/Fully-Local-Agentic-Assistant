package android.graphics

import org.jetbrains.skia.Image

/**
 * Desktop stand-in for android.graphics.Canvas: the same calls on the Skia canvas Compose draws with (Android's
 * Canvas is a thin layer over Skia as well, so shapes, text and mesh warps come out the same).
 */
class Canvas private constructor(val sk: org.jetbrains.skia.Canvas, val width: Int, val height: Int, private val surface: org.jetbrains.skia.Surface?, private val target: Bitmap?) {
    constructor(sk: org.jetbrains.skia.Canvas, width: Int = 0, height: Int = 0) : this(sk, width, height, null, null)
    /** Drawing into a bitmap (name art, offscreen frames): a raster Skia surface whose pixels are copied back after each call. */
    constructor(b: Bitmap) : this(org.jetbrains.skia.Surface.makeRaster(org.jetbrains.skia.ImageInfo.makeN32Premul(b.width, b.height)), b)
    private constructor(s: org.jetbrains.skia.Surface, b: Bitmap) : this(s.canvas, b.width, b.height, s, b) {
        s.canvas.drawImage(b.skImage(), 0f, 0f)
    }
    private fun done() {
        val s = surface ?: return; val b = target ?: return
        val bytes = s.makeImageSnapshot().let { img ->
            val bm = org.jetbrains.skia.Bitmap(); bm.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(b.width, b.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
            img.readPixels(bm, 0, 0); bm.readPixels(bm.imageInfo, b.width * 4, 0, 0)!! }
        val px = IntArray(b.width * b.height); java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(px)
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }
    fun save(): Int = sk.save()
    fun restore() { sk.restore() }
    fun restoreToCount(n: Int) { sk.restoreToCount(n) }
    fun translate(x: Float, y: Float) { sk.translate(x, y) }
    fun scale(sx: Float, sy: Float) { sk.scale(sx, sy) }
    fun scale(sx: Float, sy: Float, px: Float, py: Float) { sk.translate(px, py); sk.scale(sx, sy); sk.translate(-px, -py) }
    fun rotate(deg: Float) { sk.rotate(deg) }
    fun rotate(deg: Float, px: Float, py: Float) { sk.rotate(deg, px, py) }
    fun clipPath(p: Path): Boolean { sk.clipPath(p.sk, org.jetbrains.skia.ClipMode.INTERSECT, true); return true }
    fun clipRect(l: Float, t: Float, r: Float, b: Float): Boolean { sk.clipRect(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b)); return true }
    fun clipRect(r: RectF) = clipRect(r.left, r.top, r.right, r.bottom)
    fun drawColor(c: Int) { sk.clear(c); done() }
    fun drawColor(c: Int, mode: PorterDuff.Mode) { sk.drawPaint(org.jetbrains.skia.Paint().apply { color = c; blendMode = if (mode == PorterDuff.Mode.CLEAR) org.jetbrains.skia.BlendMode.CLEAR else org.jetbrains.skia.BlendMode.SRC_OVER }); done() }
    fun drawCircle(cx: Float, cy: Float, r: Float, p: Paint) { sk.drawCircle(cx, cy, r, p.sk()); done() }
    fun drawOval(r: RectF, p: Paint) { sk.drawOval(r.sk(), p.sk()); done() }
    fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint) { sk.drawOval(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b), p.sk()); done() }
    fun drawRect(r: RectF, p: Paint) { sk.drawRect(r.sk(), p.sk()); done() }
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { sk.drawRect(org.jetbrains.skia.Rect.makeLTRB(l, t, r, b), p.sk()); done() }
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) { sk.drawRRect(org.jetbrains.skia.RRect.makeLTRB(r.left, r.top, r.right, r.bottom, rx, ry), p.sk()); done() }
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) { sk.drawRRect(org.jetbrains.skia.RRect.makeLTRB(l, t, r, b, rx, ry), p.sk()); done() }
    fun drawArc(r: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) { sk.drawArc(r.left, r.top, r.right, r.bottom, start, sweep, useCenter, p.sk()); done() }
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) { sk.drawLine(x0, y0, x1, y1, p.sk()); done() }
    fun drawPath(path: Path, p: Paint) { sk.drawPath(path.sk, p.sk()); done() }
    fun drawText(s: String, x: Float, y: Float, p: Paint) {
        val f = p.font(); val w = if (p.textAlign == Paint.Align.LEFT) 0f else f.measureTextWidth(s)
        sk.drawString(s, x - when (p.textAlign) { Paint.Align.CENTER -> w / 2; Paint.Align.RIGHT -> w; else -> 0f }, y, f, p.sk())
        done()
    }
    private fun sampling(p: Paint?) = if (p == null || p.isFilterBitmap) org.jetbrains.skia.SamplingMode.LINEAR else org.jetbrains.skia.SamplingMode.DEFAULT
    private fun imgPaint(p: Paint?) = org.jetbrains.skia.Paint().apply { isAntiAlias = p?.isAntiAlias ?: false; alpha = p?.alpha ?: 255 }
    fun drawBitmap(b: Bitmap, left: Float, top: Float, p: Paint?) { sk.drawImageRect(b.skImage(), org.jetbrains.skia.Rect.makeWH(b.width.toFloat(), b.height.toFloat()), org.jetbrains.skia.Rect.makeXYWH(left, top, b.width.toFloat(), b.height.toFloat()), sampling(p), imgPaint(p), true); done() }
    fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) {
        val img = b.skImage()
        val s = src?.let { org.jetbrains.skia.Rect.makeLTRB(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) } ?: org.jetbrains.skia.Rect.makeWH(b.width.toFloat(), b.height.toFloat())
        sk.drawImageRect(img, s, dst.sk(), sampling(p), imgPaint(p), true)
        done()
    }
    fun drawBitmap(b: Bitmap, src: Rect?, dst: Rect, p: Paint?) = drawBitmap(b, src, RectF(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()), p)
    /** Android's mesh warp: a (meshW+1) x (meshH+1) grid of points over the bitmap, drawn as textured triangles. */
    fun drawBitmapMesh(b: Bitmap, meshW: Int, meshH: Int, verts: FloatArray, vertOffset: Int, colors: IntArray?, colorOffset: Int, p: Paint?) {
        val cols = meshW + 1; val rows = meshH + 1
        val pos = FloatArray(cols * rows * 2) { verts[vertOffset + it] }
        val tex = FloatArray(cols * rows * 2)
        for (r in 0 until rows) for (c in 0 until cols) { val i = (r * cols + c) * 2; tex[i] = b.width * c.toFloat() / meshW; tex[i + 1] = b.height * r.toFloat() / meshH }
        val idx = ShortArray(meshW * meshH * 6); var k = 0
        for (r in 0 until meshH) for (c in 0 until meshW) { val a = r * cols + c; val bb = a + 1; val cc = a + cols; val d = cc + 1
            idx[k++] = a.toShort(); idx[k++] = bb.toShort(); idx[k++] = cc.toShort(); idx[k++] = bb.toShort(); idx[k++] = d.toShort(); idx[k++] = cc.toShort() }
        val paint = imgPaint(p).apply { shader = b.skImage().makeShader(org.jetbrains.skia.FilterTileMode.CLAMP, org.jetbrains.skia.FilterTileMode.CLAMP, sampling(p), null); isAntiAlias = true }
        sk.drawVertices(org.jetbrains.skia.VertexMode.TRIANGLES, pos, null, tex, idx, org.jetbrains.skia.BlendMode.MODULATE, paint)
        done()
    }
}
class PorterDuff { enum class Mode { CLEAR, SRC, SRC_OVER, DST_IN, DST_OUT, MULTIPLY } }
