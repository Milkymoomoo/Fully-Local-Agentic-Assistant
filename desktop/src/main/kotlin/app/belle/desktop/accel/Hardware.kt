package app.belle.desktop.accel

import app.belle.desktop.Paths
import java.io.File
import java.util.concurrent.TimeUnit

object HardwareProbe {
    @Volatile private var cached: Hardware? = null

    fun get(): Hardware = cached ?: probe().also { cached = it }

    private fun run(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); "" } else out
    }.getOrDefault("")

    private fun probe(): Hardware {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val ramMb = os.totalMemorySize / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        var names = emptyList<String>(); var vram = 0L; var nvidia = false; var vulkan = false; var dx12 = false
        val smi = run("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")
        if (smi.isNotBlank() && !smi.contains("not", true)) {
            nvidia = true
            smi.lines().filter { it.contains(',') }.forEach { l -> val (n, m) = l.split(',', limit = 2); names = names + n.trim(); vram = maxOf(vram, m.trim().toLongOrNull() ?: 0) }
        }
        if (Paths.isWindows) {
            val sys = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
            vulkan = File(sys, "vulkan-1.dll").exists()
            dx12 = File(sys, "d3d12.dll").exists()
            nvidia = nvidia || File(sys, "nvcuda.dll").exists()
            val ps = run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_VideoController).Name")
            val more = ps.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("Basic Display", true) }
            names = (names + more).distinctBy { it.lowercase() }
        } else {
            vulkan = listOf("/usr/lib/x86_64-linux-gnu/libvulkan.so.1", "/usr/lib/libvulkan.so.1").any { File(it).exists() }
        }
        return Hardware(nvidia, names, vram, vulkan, dx12, ramMb, cores)
    }
}
