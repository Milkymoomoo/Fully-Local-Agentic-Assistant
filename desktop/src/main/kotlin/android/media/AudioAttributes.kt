package android.media

/** Desktop stand-in: usage/content type are kept for nothing but source compatibility (Windows has one output mix). */
class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(u: Int) = this
        fun setContentType(c: Int) = this
        fun build() = AudioAttributes()
    }
    companion object {
        const val USAGE_MEDIA = 1; const val USAGE_ASSISTANT = 16; const val USAGE_NOTIFICATION = 5; const val USAGE_ALARM = 4
        const val CONTENT_TYPE_SPEECH = 1; const val CONTENT_TYPE_MUSIC = 2; const val CONTENT_TYPE_SONIFICATION = 4
    }
}
