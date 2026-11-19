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
    /**
     * Bundled assets (the phone app's `assets/` folder, copied in by the build): `<install>/app/resources/assets` in
     * the installed / portable program (found through the launcher's resources property, or next to the program's
     * jar when that property is missing), else the Android app's source folder (dev runs and tests).
     */
    override val assets: AssetManager by lazy { AssetManager(assetRoots()) }
    fun assetRoots(): List<File> {
        val packaged = System.getProperty("compose.application.resources.dir")?.let { File(it, "assets") }
        val besideJar = runCatching { File(DesktopContext::class.java.protectionDomain.codeSource.location.toURI()).parentFile?.let { File(it, "resources/assets") } }.getOrNull()
        val dev = listOf(File("app/src/main/assets"), File("../app/src/main/assets"), File("desktop/resources/common/assets"), File("resources/common/assets"))
        return listOfNotNull(packaged, besideJar).plus(dev).filter { it.isDirectory }.map { it.absoluteFile }.take(1)   // one folder, never a mix
    }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.computeIfAbsent(name) { JsonPrefs(File(Paths.profiles, "$it.json")) }
}
