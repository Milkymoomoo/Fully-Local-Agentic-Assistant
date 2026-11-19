package app.belle.desktop

import java.io.File

/** Points the app's folders at a throwaway directory for the test JVM (must run before the first Paths use). */
object TestHome {
    val dir: File by lazy { File(System.getProperty("java.io.tmpdir"), "belle-test-${ProcessHandle.current().pid()}").apply { deleteRecursively(); mkdirs(); deleteOnExit() } }
    fun use() { if (System.getProperty("belle.home") == null) System.setProperty("belle.home", dir.path) }
}
