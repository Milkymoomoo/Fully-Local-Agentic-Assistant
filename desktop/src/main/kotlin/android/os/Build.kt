package android.os

/** Desktop stand-in: describes the PC; SDK_INT 0 keeps Android-version-gated features off. */
object Build {
    @JvmField val MANUFACTURER: String = System.getProperty("os.name").orEmpty()
    @JvmField val MODEL: String = System.getProperty("os.version").orEmpty()
    @JvmField val SUPPORTED_ABIS = arrayOf(System.getProperty("os.arch").orEmpty())
    object VERSION { @JvmField val SDK_INT = 0; @JvmField val RELEASE: String = System.getProperty("os.version").orEmpty() }
    object VERSION_CODES { const val O = 26; const val P = 28; const val Q = 29; const val R = 30; const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34 }
}
class Bundle { private val m = HashMap<String, Any?>(); fun putString(k: String, v: String?) { m[k] = v }; fun getString(k: String) = m[k] as? String }
