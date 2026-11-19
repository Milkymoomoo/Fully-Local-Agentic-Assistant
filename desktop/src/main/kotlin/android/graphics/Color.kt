package android.graphics

/** Desktop stand-in for android.graphics.Color (packed ARGB helpers). */
object Color {
    const val BLACK = -0x1000000; const val WHITE = -1; const val TRANSPARENT = 0
    @JvmStatic fun HSVToColor(hsv: FloatArray): Int = HSVToColor(255, hsv)
    @JvmStatic fun HSVToColor(a: Int, hsv: FloatArray): Int {
        val rgb = java.awt.Color.HSBtoRGB(((hsv[0] % 360f) + 360f) % 360f / 360f, hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))
        return (a shl 24) or (rgb and 0xFFFFFF)
    }
    @JvmStatic fun colorToHSV(c: Int, out: FloatArray) { val f = java.awt.Color.RGBtoHSB(red(c), green(c), blue(c), null); out[0] = f[0] * 360f; out[1] = f[1]; out[2] = f[2] }
    @JvmStatic fun red(c: Int) = (c shr 16) and 255
    @JvmStatic fun green(c: Int) = (c shr 8) and 255
    @JvmStatic fun blue(c: Int) = c and 255
    @JvmStatic fun alpha(c: Int) = (c ushr 24) and 255
    @JvmStatic fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int) = ((a and 255) shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
}
