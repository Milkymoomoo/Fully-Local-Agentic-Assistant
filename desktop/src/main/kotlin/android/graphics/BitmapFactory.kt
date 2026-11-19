package android.graphics

import java.io.File
import java.io.InputStream

/** Desktop stand-in: decodes with Skia's codecs (PNG, JPEG, WebP, GIF first frame, BMP, ICO; the same family Android uses), ImageIO as a fallback. */
object BitmapFactory {
    class Options { @JvmField var inJustDecodeBounds = false; @JvmField var inSampleSize = 1; @JvmField var outWidth = 0; @JvmField var outHeight = 0; @JvmField var inPremultiplied = true; @JvmField var inMutable = false; @JvmField var inPreferredConfig: Bitmap.Config? = null; @JvmField var outMimeType: String? = null }

    private fun decode(bytes: ByteArray, o: Options?): Bitmap? {
        val codec = runCatching { org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) }.getOrNull()
        if (codec == null) {   // formats Skia doesn't know: ImageIO
            val b = runCatching { javax.imageio.ImageIO.read(bytes.inputStream())?.let(Bitmap::fromImage) }.getOrNull() ?: return null
            o?.outWidth = b.width; o?.outHeight = b.height
            return if (o?.inJustDecodeBounds == true) null else sample(b, o)
        }
        o?.outWidth = codec.width; o?.outHeight = codec.height; o?.outMimeType = "image/" + codec.encodedImageFormat.name.lowercase()
        if (o?.inJustDecodeBounds == true) return null
        val sb = org.jetbrains.skia.Bitmap(); sb.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(codec.width, codec.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
        codec.readPixels(sb, 0)
        return sample(Bitmap.fromSkia(sb), o)
    }
    private fun sample(b: Bitmap, o: Options?): Bitmap {
        val k = (o?.inSampleSize ?: 1).coerceAtLeast(1)
        return if (k == 1) b else Bitmap.createScaledBitmap(b, (b.width / k).coerceAtLeast(1), (b.height / k).coerceAtLeast(1), true)
    }

    @JvmStatic fun decodeFile(path: String): Bitmap? = decodeFile(path, null)
    @JvmStatic fun decodeFile(path: String, o: Options?): Bitmap? = File(path).takeIf { it.isFile }?.let { runCatching { decode(it.readBytes(), o) }.getOrNull() }
    @JvmStatic fun decodeStream(s: InputStream?): Bitmap? = decodeStream(s, null, null)
    @JvmStatic fun decodeStream(s: InputStream?, pad: Any?, o: Options?): Bitmap? = s?.use { runCatching { decode(it.readBytes(), o) }.getOrNull() }
    @JvmStatic fun decodeByteArray(d: ByteArray, off: Int, len: Int): Bitmap? = decodeByteArray(d, off, len, null)
    @JvmStatic fun decodeByteArray(d: ByteArray, off: Int, len: Int, o: Options?): Bitmap? = runCatching { decode(d.copyOfRange(off, off + len), o) }.getOrNull()
    @JvmStatic fun decodeResource(res: android.content.res.Resources, id: Int): Bitmap? = decodeResource(res, id, null)
    @JvmStatic fun decodeResource(res: android.content.res.Resources, id: Int, o: Options?): Bitmap? =
        BitmapFactory::class.java.getResourceAsStream("/" + app.belle.assistant.R.drawable.path(id))?.let { decodeStream(it, null, o) }
}
