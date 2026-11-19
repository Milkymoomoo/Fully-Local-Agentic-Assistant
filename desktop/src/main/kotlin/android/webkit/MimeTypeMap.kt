package android.webkit

/** Desktop stand-in: picked files always carry their extension, so no MIME mapping is needed. */
class MimeTypeMap private constructor() {
    fun getExtensionFromMimeType(t: String?): String? = null
    companion object { private val one = MimeTypeMap(); @JvmStatic fun getSingleton() = one }
}
