package android.content.pm

/** Desktop stand-in: FLAG_DEBUGGABLE is set for development runs (-Dbelle.debug=1), never in the packaged program. */
class ApplicationInfo {
    @JvmField var flags: Int = if (System.getProperty("belle.debug") == "1") FLAG_DEBUGGABLE else 0
    companion object { const val FLAG_DEBUGGABLE = 2 }
}
