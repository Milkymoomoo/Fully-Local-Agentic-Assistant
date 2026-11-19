package app.belle.assistant.core

/**
 * How much of the avatar shows in a frame (Home circle, overlay bubble, widget).
 * zoom 1 = the whole picture fits; >1 = closer. (cx, cy) = picture point at the frame centre.
 * 3D: camera distance (×model size), height (−1..1 of model height) and orbit angle (degrees).
 */
data class Framing(
    val shape: String = "rounded", val zoom: Float = 1f, val cx: Float = 0.5f, val cy: Float = 0.5f,
    val dist: Float = 2.2f, val height: Float = 0.25f, val angle: Float = 0f,
) {
    fun toLine() = "$shape,$zoom,$cx,$cy,$dist,$height,$angle"

    /**
     * Destination rect of the whole picture inside a frame of [fw]×[fh] pixels:
     * returns (left, top, width, height).
     */
    fun place(iw: Float, ih: Float, fw: Float, fh: Float): FloatArray {
        val s = minOf(fw / iw, fh / ih) * zoom
        val w = iw * s; val h = ih * s
        return floatArrayOf(fw / 2 - cx * w, fh / 2 - cy * h, w, h)
    }

    companion object {
        val SHAPES = listOf("circle" to "Circle", "rounded" to "Rounded square", "full" to "Full picture (no crop)")
        val SURFACES = listOf("home" to "Home screen", "bubble" to "Floating bubble", "widget" to "Widget")
        /** Bob's default: the whole upper scene (horns, face, chest on the desk, hands). */
        val DEFAULT = Framing()
        fun parse(s: String?): Framing? = runCatching {
            val p = s!!.split(",")
            Framing(p[0], p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat())
        }.getOrNull()
    }
}
