package android.content.pm

/** Desktop stand-in: no other Android apps to launch (the Downloader companion is phone-only). */
object PackageManager {
    const val PERMISSION_GRANTED = 0; const val PERMISSION_DENIED = -1
    fun getLaunchIntentForPackage(pkg: String): android.content.Intent? = null
}
