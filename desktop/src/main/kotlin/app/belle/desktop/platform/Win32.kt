package app.belle.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/** The few Win32 calls the app makes directly (JNA). Only called on Windows. */
object Win32 {
    const val ES_CONTINUOUS = 0x80000000.toInt()
    const val ES_SYSTEM_REQUIRED = 0x00000001
    const val ES_DISPLAY_REQUIRED = 0x00000002

    @Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class PowerStatus : Structure() {
        @JvmField var ACLineStatus: Byte = 0; @JvmField var BatteryFlag: Byte = 0; @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var SystemStatusFlag: Byte = 0; @JvmField var BatteryLifeTime: Int = 0; @JvmField var BatteryFullLifeTime: Int = 0
    }
    @Structure.FieldOrder("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
        "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")
    class MemCounters : Structure() {
        @JvmField var cb: Int = 0; @JvmField var PageFaultCount: Int = 0
        @JvmField var PeakWorkingSetSize: Long = 0; @JvmField var WorkingSetSize: Long = 0; @JvmField var QuotaPeakPagedPoolUsage: Long = 0
        @JvmField var QuotaPagedPoolUsage: Long = 0; @JvmField var QuotaPeakNonPagedPoolUsage: Long = 0; @JvmField var QuotaNonPagedPoolUsage: Long = 0
        @JvmField var PagefileUsage: Long = 0; @JvmField var PeakPagefileUsage: Long = 0
    }
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Int): Int
        fun GetSystemPowerStatus(s: PowerStatus): Boolean
        fun GetCurrentProcess(): Pointer
        fun K32GetProcessMemoryInfo(p: Pointer, c: MemCounters, cb: Int): Boolean
    }
    private interface User32 : Library { fun keybd_event(vk: Byte, scan: Byte, flags: Int, extra: Pointer?) }
    private val k32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val u32 by lazy { Native.load("user32", User32::class.java) }

    fun setExecutionState(flags: Int): Int = k32.SetThreadExecutionState(flags)
    fun powerStatus(): PowerStatus = PowerStatus().also { k32.GetSystemPowerStatus(it) }
    fun workingSetBytes(): Long = MemCounters().let { c -> c.cb = c.size(); if (k32.K32GetProcessMemoryInfo(k32.GetCurrentProcess(), c, c.cb)) c.WorkingSetSize else -1 }
    fun keyTap(vk: Int) { u32.keybd_event(vk.toByte(), 0, 0, null); u32.keybd_event(vk.toByte(), 0, 2, null) }
}
