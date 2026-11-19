package android.content.res

import java.io.InputStream

/** Desktop stand-in: assets are read from the app's bundled `assets/` (classpath resources or a folder). */
class AssetManager(private val root: java.io.File?) {
    fun open(name: String): InputStream =
        root?.let { java.io.File(it, name) }?.takeIf { it.isFile }?.inputStream()
            ?: AssetManager::class.java.getResourceAsStream("/assets/$name")
            ?: throw java.io.FileNotFoundException(name)
    /** Bundled assets are plain files in the install folder, so this works only for those (as on Android for uncompressed assets). */
    fun openFd(name: String): AssetFileDescriptor = AssetFileDescriptor(root?.let { java.io.File(it, name) }?.takeIf { it.isFile } ?: throw java.io.FileNotFoundException(name))
    fun list(dir: String): Array<String> = root?.let { java.io.File(it, dir).list() } ?: emptyArray()
}
