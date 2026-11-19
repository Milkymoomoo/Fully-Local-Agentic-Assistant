package app.belle.desktop.platform

import java.io.File

/** Resident memory of this process: /proc on Linux, the working set (GetProcessMemoryInfo) on Windows. */
object ProcMem {
    fun rssKb(): Long = runCatching {
        if (app.belle.desktop.Paths.isWindows) {
            Win32.workingSetBytes() / 1024
        } else File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toLong()
    }.getOrDefault(-1)
    fun rssMb(): Int = (rssKb() / 1024).toInt()
}
