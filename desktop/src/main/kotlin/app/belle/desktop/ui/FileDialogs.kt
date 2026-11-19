package app.belle.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Native file dialogs (the Windows "Open" / "Save as" boxes), filtered by the MIME types the shared pages ask for. */
object FileDialogs {
    private val EXT = mapOf(
        "image/" to listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif"), "video/" to listOf("mp4", "webm", "mkv", "mov", "gif"),
        "audio/" to listOf("mp3", "ogg", "wav", "m4a", "flac", "opus"), "application/epub+zip" to listOf("epub"), "application/pdf" to listOf("pdf"),
        "text/" to listOf("txt", "md", "json", "html", "htm"), "application/json" to listOf("json"), "model/gltf-binary" to listOf("glb"),
        "font/" to listOf("ttf", "otf"), "application/zip" to listOf("zip"),
    )
    fun extsFor(mimes: List<String>): List<String>? = if (mimes.any { it == "*/*" || it.isBlank() }) null
        else mimes.flatMap { m -> EXT.entries.filter { (k, _) -> if (k.endsWith("/")) m.startsWith(k) else m == k }.flatMap { it.value } }.distinct().ifEmpty { null }

    /** Last folder used, so the next dialog opens there. */
    @Volatile private var lastDir: String? = System.getProperty("user.home")

    fun open(mimes: List<String>, multiple: Boolean, done: (List<File>) -> Unit) = UiThread.post {
        val exts = extsFor(mimes)
        val d = FileDialog(null as Frame?, "Choose a file", FileDialog.LOAD).apply {
            isMultipleMode = multiple; directory = lastDir
            if (exts != null) { setFilenameFilter { _, n -> exts.any { n.endsWith(".$it", true) } }; file = exts.joinToString(";") { "*.$it" } }
        }
        d.isVisible = true
        val files = d.files.orEmpty().toList()
        files.firstOrNull()?.parent?.let { lastDir = it }
        done(files)
    }
    fun save(suggested: String, done: (File?) -> Unit) = UiThread.post {
        val d = FileDialog(null as Frame?, "Save as", FileDialog.SAVE).apply { file = suggested; directory = lastDir }
        d.isVisible = true
        val f = d.file?.let { File(d.directory, it) }
        f?.parent?.let { lastDir = it }
        done(f)
    }
    fun folder(done: (File?) -> Unit) = UiThread.post {
        val c = JFileChooser(lastDir).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose a folder" }
        done(if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile.also { lastDir = it.path } else null)
    }
}
