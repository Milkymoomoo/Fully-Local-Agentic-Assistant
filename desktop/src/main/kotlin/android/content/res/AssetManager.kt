package android.content.res

import java.io.File
import java.io.InputStream

/**
 * Desktop stand-in for the phone's AssetManager: the Android app's `app/src/main/assets` folder, which the build
 * copies into the program (`<install>/app/resources/assets`; dev runs read the source folder). [roots] are searched
 * in order; a file that is in none of them is looked up on the classpath under `/assets/` as a last resort.
 * Like Android, [list] returns a folder's entries (sorted) and an empty array for a file or a missing path.
 */
class AssetManager(roots: List<File>) {
    constructor(root: File?) : this(listOfNotNull(root))
    val roots: List<File> = roots.filter { it.isDirectory }

    private fun clean(name: String) = name.replace('\\', '/').trim('/')
    private fun file(name: String): File? = roots.firstNotNullOfOrNull { r -> File(r, clean(name)).takeIf { it.isFile } }

    fun open(name: String): InputStream =
        file(name)?.inputStream()
            ?: AssetManager::class.java.getResourceAsStream("/assets/${clean(name)}")
            ?: throw java.io.FileNotFoundException("$name (asset not bundled; looked in ${roots.joinToString { it.path }.ifEmpty { "no asset folder" }})")
    fun open(name: String, mode: Int): InputStream = open(name)
    /** Bundled assets are plain files in the install folder, so this works like Android's for uncompressed assets. */
    fun openFd(name: String): AssetFileDescriptor = AssetFileDescriptor(file(name) ?: throw java.io.FileNotFoundException(name))
    fun list(dir: String): Array<String> =
        roots.flatMap { r -> File(r, clean(dir)).takeIf { it.isDirectory }?.list()?.toList().orEmpty() }.distinct().sorted().toTypedArray()

    companion object { const val ACCESS_STREAMING = 2; const val ACCESS_BUFFER = 3 }
}
