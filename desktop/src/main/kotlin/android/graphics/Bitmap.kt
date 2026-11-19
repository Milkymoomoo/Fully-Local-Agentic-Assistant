package android.graphics

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.OutputStream

/**
 * Desktop stand-in for android.graphics.Bitmap: non-premultiplied ARGB pixels in an IntArray (what Android's
 * getPixels/setPixels return), converted to/from [BufferedImage] for scaling and encoding.
 */
class Bitmap private constructor(val width: Int, val height: Int, val config: Config, internal val px: IntArray) {
    enum class Config { ARGB_8888, RGB_565, ALPHA_8, HARDWARE, RGBA_F16 }
    enum class CompressFormat { PNG, JPEG, WEBP, WEBP_LOSSLESS, WEBP_LOSSY }
    var isRecycled = false; private set
    val byteCount get() = width * height * 4
    val isMutable get() = true
    fun hasAlpha() = config != Config.RGB_565

    fun getPixel(x: Int, y: Int) = px[y * width + x]
    fun setPixel(x: Int, y: Int, c: Int) { px[y * width + x] = c; gen++ }
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (r in 0 until h) System.arraycopy(px, (y + r) * width + x, out, offset + r * stride, w)
    }
    fun setPixels(src: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (r in 0 until h) System.arraycopy(src, offset + r * stride, px, (y + r) * width + x, w)
        gen++
    }
    fun eraseColor(c: Int) { px.fill(c); gen++ }
    /** RGBA bytes (GL readback order) → pixels; GL alpha is straight, as Android's un-premultiplied view. */
    fun copyPixelsFromBuffer(b: java.nio.Buffer) {
        val bb = (b as java.nio.ByteBuffer).duplicate()
        for (i in 0 until width * height) { val r = bb.get().toInt() and 255; val g = bb.get().toInt() and 255; val bl = bb.get().toInt() and 255; val a = bb.get().toInt() and 255
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl }
        gen++
    }

    /** Pixel edits bump this so the Skia copy used for drawing ([skImage]) is rebuilt only when needed. */
    @Volatile private var gen = 0
    private var skGen = -1
    private var sk: org.jetbrains.skia.Image? = null
    /** The pixels as a Skia image (unpremultiplied BGRA, i.e. ARGB ints in little-endian order) for drawing. */
    @Synchronized fun skImage(): org.jetbrains.skia.Image {
        sk?.let { if (skGen == gen) return it }
        val bb = java.nio.ByteBuffer.allocate(px.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.asIntBuffer().put(px)
        val img = org.jetbrains.skia.Image.makeRaster(org.jetbrains.skia.ImageInfo.makeN32(width, height, org.jetbrains.skia.ColorAlphaType.UNPREMUL), bb.array(), width * 4)
        sk = img; skGen = gen; return img   // the old one is freed by GC: a pending GPU draw may still use it
    }
    fun recycle() { isRecycled = true }
    fun copy(c: Config, mutable: Boolean) = Bitmap(width, height, c, px.copyOf())

    fun toImage(): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, width, height, px, 0, width) }

    fun compress(f: CompressFormat, quality: Int, out: OutputStream): Boolean {
        val img = if (f == CompressFormat.JPEG) BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, width, height, px, 0, width) } else toImage()
        return javax.imageio.ImageIO.write(img, if (f == CompressFormat.JPEG) "jpg" else "png", out)
    }

    companion object {
        @JvmStatic fun createBitmap(w: Int, h: Int, c: Config) = Bitmap(w, h, c, IntArray(w * h))
        @JvmStatic fun createBitmap(colors: IntArray, w: Int, h: Int, c: Config) = Bitmap(w, h, c, colors.copyOf(w * h))
        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int) = createBitmap(w, h, src.config).also { b ->
            for (r in 0 until h) System.arraycopy(src.px, (y + r) * src.width + x, b.px, r * w, w) }
        @JvmStatic fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            if (w == src.width && h == src.height) return src.copy(src.config, true)
            val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = dst.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(src.toImage(), 0, 0, w, h, null); g.dispose()
            return fromImage(dst)
        }
        /** From a Skia bitmap in N32 unpremultiplied layout (what the image codecs decode to). */
        @JvmStatic fun fromSkia(sb: org.jetbrains.skia.Bitmap): Bitmap {
            val b = createBitmap(sb.width, sb.height, Config.ARGB_8888)
            val bytes = sb.readPixels(org.jetbrains.skia.ImageInfo.makeN32(sb.width, sb.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL), sb.width * 4, 0, 0)!!
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(b.px); return b
        }
        @JvmStatic fun fromImage(img: BufferedImage): Bitmap {
            val b = createBitmap(img.width, img.height, Config.ARGB_8888)
            img.getRGB(0, 0, img.width, img.height, b.px, 0, img.width); return b
        }
    }
}
