package android.content

import java.io.File

/** Desktop stand-in for android.content.Context: only what the shared code uses. Implemented by [app.belle.desktop.DesktopContext]. */
abstract class Context {
    abstract val applicationContext: Context
    abstract val filesDir: File
    abstract val cacheDir: File
    abstract val assets: android.content.res.AssetManager
    open val contentResolver: ContentResolver get() = ContentResolver
    open val applicationInfo: android.content.pm.ApplicationInfo get() = appInfo
    /** Web links → the default browser; a shared file → shown in Explorer; an app screen → its desktop window. Android system screens do nothing (their buttons are hidden on the PC). */
    open fun startActivity(i: Intent) = app.belle.desktop.ui.Screens.start(this, i)
    open val noBackupFilesDir: File get() = filesDir
    open val resources: android.content.res.Resources get() = android.content.res.Resources
    open val packageName: String get() = "app.belle.assistant"
    fun getDrawable(id: Int): android.graphics.drawable.Drawable? = android.graphics.BitmapFactory.decodeResource(resources, id)?.let { android.graphics.drawable.BitmapDrawable(it) }
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    open fun <T> getSystemService(c: Class<T>): T = when (c) {
        android.app.ActivityManager::class.java -> c.cast(android.app.ActivityManager())
        android.media.AudioManager::class.java -> c.cast(audioManager)
        // every other system service the shared screens ask for (PowerManager, RoleManager, AlarmManager, ...) is a
        // desktop stand-in class with a no-argument constructor; one shared instance each, never a crash
        else -> c.cast(services.computeIfAbsent(c) { runCatching { it.getDeclaredConstructor().newInstance() }.getOrElse { e -> throw UnsupportedOperationException("no ${c.name} on the desktop", e) } })
    }
    companion object {
        const val MODE_PRIVATE = 0; private val appInfo = android.content.pm.ApplicationInfo(); private val audioManager = android.media.AudioManager()
        private val services = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    }
}

/** Desktop stand-in: every "content" Uri on the desktop is a plain file (from a file chooser or drag and drop). */
object ContentResolver {
    /** Folder listings for [android.provider.DocumentsContract] (a folder picked with "Choose a folder"); nothing else. */
    fun query(u: android.net.Uri, cols: Array<String>?, sel: String?, args: Array<String>?, sort: String?): android.database.Cursor? =
        android.provider.DocumentsContract.children(u)?.let { files -> android.database.ListCursor(files.map { arrayOf<Any?>(it.path, it.name) }) }
    fun getType(u: android.net.Uri): String? = null
    fun openInputStream(u: android.net.Uri): java.io.InputStream? = u.path?.let { java.io.File(it) }?.takeIf { it.isFile }?.inputStream()
    fun openOutputStream(u: android.net.Uri): java.io.OutputStream? = u.path?.let { java.io.File(it) }?.outputStream()
}
