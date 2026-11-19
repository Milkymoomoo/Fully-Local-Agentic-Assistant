package android.graphics

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/** Desktop stand-in for android.graphics.ImageDecoder (stills and animated GIF / WebP through Skia's codecs). */
class ImageDecoder private constructor() {
    var allocator = ALLOCATOR_DEFAULT
    var isMutableRequired = false
    fun setTargetSize(w: Int, h: Int) { target = w to h }
    internal var target: Pair<Int, Int>? = null
    class Source internal constructor(internal val bytes: () -> ByteArray)
    class ImageInfo(val size: android.util.Size, val mimeType: String?)
    fun interface OnHeaderDecodedListener { fun onHeaderDecoded(decoder: ImageDecoder, info: ImageInfo, source: Source) }
    companion object {
        const val ALLOCATOR_DEFAULT = 0; const val ALLOCATOR_SOFTWARE = 1; const val ALLOCATOR_SHARED_MEMORY = 2; const val ALLOCATOR_HARDWARE = 3
        @JvmStatic fun createSource(f: File) = Source { f.readBytes() }
        @JvmStatic fun createSource(b: java.nio.ByteBuffer) = Source { val d = b.duplicate(); ByteArray(d.remaining()).also { d.get(it) } }
        private fun codec(s: Source) = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(s.bytes()))
        private fun frame(c: org.jetbrains.skia.Codec, i: Int): Bitmap {
            val sb = org.jetbrains.skia.Bitmap(); sb.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(c.width, c.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
            c.readPixels(sb, i)
            return Bitmap.fromSkia(sb)
        }
        private fun header(c: org.jetbrains.skia.Codec, s: Source, l: OnHeaderDecodedListener?): ImageDecoder =
            ImageDecoder().also { d -> l?.onHeaderDecoded(d, ImageInfo(android.util.Size(c.width, c.height), null), s) }
        private fun sized(b: Bitmap, d: ImageDecoder) = d.target?.let { (w, h) -> Bitmap.createScaledBitmap(b, w, h, true) } ?: b
        @JvmStatic fun decodeBitmap(s: Source, l: OnHeaderDecodedListener? = null): Bitmap {
            val c = codec(s); val d = header(c, s, l); return sized(frame(c, 0), d)
        }
        @JvmStatic fun decodeDrawable(s: Source, l: OnHeaderDecodedListener? = null): Drawable {
            val c = codec(s); val d = header(c, s, l)
            if (c.frameCount <= 1) return BitmapDrawable(sized(frame(c, 0), d))
            val info = c.framesInfo
            return AnimatedImageDrawable(List(c.frameCount) { sized(frame(c, it), d) }, IntArray(c.frameCount) { info[it].duration.coerceAtLeast(20) })
        }
    }
}
