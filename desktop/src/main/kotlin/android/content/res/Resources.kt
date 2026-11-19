package android.content.res

/** Desktop stand-in: density 1 (Compose handles the PC's display scaling). */
object Resources {
    class DisplayMetrics { @JvmField val density = 1f; @JvmField val widthPixels = 1920; @JvmField val heightPixels = 1080; @JvmField val densityDpi = 160 }
    val displayMetrics = DisplayMetrics()
}
