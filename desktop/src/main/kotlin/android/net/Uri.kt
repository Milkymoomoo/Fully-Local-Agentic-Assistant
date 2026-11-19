package android.net

import java.io.File

/** Desktop stand-in for android.net.Uri: only file paths and plain URLs (no content providers on the desktop). */
class Uri private constructor(private val s: String) {
    val scheme: String? get() = s.substringBefore(':', "").ifEmpty { null }
    val path: String? get() = if (s.startsWith("file:")) File(java.net.URI(s)).path else s
    val lastPathSegment: String? get() = path?.let { File(it).name }
    override fun toString() = s
    override fun equals(other: Any?) = other is Uri && other.s == s
    override fun hashCode() = s.hashCode()
    companion object {
        @JvmStatic fun parse(s: String) = Uri(s)
        @JvmStatic fun fromFile(f: File) = Uri(f.toURI().toString())
    }
}
