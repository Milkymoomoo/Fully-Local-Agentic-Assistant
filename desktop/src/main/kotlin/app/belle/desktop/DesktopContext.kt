package app.belle.desktop

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The single desktop [Context]: the app's files, cache, bundled assets and per-profile settings files. */
object DesktopContext : Context() {
    private val prefs = ConcurrentHashMap<String, JsonPrefs>()
    override val applicationContext: Context get() = this
    override val filesDir: File get() = Paths.files
    override val cacheDir: File get() = Paths.cache
    /** Bundled assets: `<install>/app/resources/assets` (packaged) or the Android app's assets folder (dev runs). */
    override val assets: AssetManager by lazy {
        val packaged = System.getProperty("compose.application.resources.dir")?.let { File(it, "assets") }
        val dev = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).firstOrNull { it.isDirectory }
        AssetManager(packaged?.takeIf { it.isDirectory } ?: dev)
    }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.computeIfAbsent(name) { JsonPrefs(File(Paths.profiles, "$it.json")) }
}
