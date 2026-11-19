package app.belle.desktop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The app's log: one rotating file in the logs folder (never conversation text: callers log events only). */
object DesktopLog {
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private const val MAX = 4L shl 20
    @Volatile var echo = System.getProperty("belle.logecho") == "1"
    private val lock = Any()
    val file: File get() = File(Paths.logs, "app.log")

    fun line(level: Char, tag: String, msg: String, t: Throwable?): Int {
        val s = buildString {
            append(LocalDateTime.now().format(fmt)).append(' ').append(level).append(' ').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(t.stackTraceToString().lines().take(12).joinToString("\n"))
        }
        if (echo || level == 'E') System.err.println(s)
        synchronized(lock) {
            runCatching {
                val f = file
                if (f.length() > MAX) { val old = File(f.parentFile, "app.1.log"); old.delete(); f.renameTo(old) }
                f.appendText(s + "\n")
            }
        }
        return 0
    }
}
