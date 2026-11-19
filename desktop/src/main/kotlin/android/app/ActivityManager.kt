package android.app

/** Desktop stand-in: GL capability and total RAM (from the OS, via the JVM's management bean). */
class ActivityManager {
    class ConfigurationInfo { val reqGlEsVersion: Int = 0x30002 }
    class MemoryInfo { @JvmField var totalMem: Long = 0; @JvmField var availMem: Long = 0; @JvmField var lowMemory = false }
    val deviceConfigurationInfo = ConfigurationInfo()
    /** Android 11+ "why did the app die" records: the PC has none (crashes are in the program's own log). */
    class ApplicationExitInfo(val reason: Int, val description: String?, val status: Int, val timestamp: Long)
    fun getHistoricalProcessExitReasons(pkg: String, pid: Int, max: Int): List<ApplicationExitInfo> = emptyList()
    fun getMemoryInfo(m: MemoryInfo) {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        m.totalMem = os?.totalMemorySize ?: Runtime.getRuntime().maxMemory()
        m.availMem = os?.freeMemorySize ?: 0
    }
}
