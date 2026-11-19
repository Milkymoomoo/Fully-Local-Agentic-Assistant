package android.content.res

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.Collections
import java.util.WeakHashMap

/** Desktop stand-in: an asset file opened as a whole file (offset 0); [fileOf] lets MediaPlayer find the file again. */
class AssetFileDescriptor(private val f: File) : java.io.Closeable {
    private val input = FileInputStream(f)
    val fileDescriptor: FileDescriptor = input.fd.also { open[it] = f }
    val startOffset = 0L
    val length = f.length()
    override fun close() { open.remove(fileDescriptor); input.close() }
    companion object {
        private val open = Collections.synchronizedMap(WeakHashMap<FileDescriptor, File>())
        fun fileOf(fd: FileDescriptor): File? = open[fd]
    }
}
