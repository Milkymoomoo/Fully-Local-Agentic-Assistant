package app.belle.desktop.platform

import app.belle.desktop.Paths
import java.io.File

/** PC actions behind the assistant's tools: opening apps/files/links, media keys, battery. */
object WinActions {
    fun open(f: File): Boolean = runCatching { java.awt.Desktop.getDesktop().open(f); true }.getOrDefault(false)
    fun openFile(f: File) = open(f)
    /** Shows the file selected in Explorer (Linux: opens its folder). */
    fun reveal(f: File): Boolean = runCatching {
        if (Paths.isWindows) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start() else java.awt.Desktop.getDesktop().open(f.parentFile); true }.getOrDefault(false)
    fun browse(url: String): Boolean = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)); true }.getOrDefault(false)
    /** ms-settings:, bingmaps:, microsoft.windows.camera: … (Windows protocol handlers). */
    fun openUri(uri: String): Boolean = if (!Paths.isWindows) false else runCatching {
        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start(); true }.getOrDefault(false)

    /** Start-menu shortcuts (Windows) or .desktop entries (Linux), best fuzzy name match. */
    fun findApp(name: String): Pair<String, File>? {
        val q = name.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim(); if (q.isEmpty()) return null
        val roots = if (Paths.isWindows) listOfNotNull(System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") })
        else listOf(File("/usr/share/applications"), File(System.getProperty("user.home"), ".local/share/applications"))
        val ext = if (Paths.isWindows) ".lnk" else ".desktop"
        val all = roots.flatMap { r -> r.walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(ext, true) }.toList() }
        fun key(f: File) = f.name.removeSuffix(ext).lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        return (all.firstOrNull { key(it) == q } ?: all.filter { key(it).startsWith(q) }.minByOrNull { key(it).length }
            ?: all.filter { q in key(it) }.minByOrNull { key(it).length })?.let { it.name.removeSuffix(ext) to it }
    }

    fun mediaKey(which: String, times: Int): Boolean {
        if (!Paths.isWindows) return false
        val vk = when (which) { "playpause" -> 0xB3; "next" -> 0xB0; "prev" -> 0xB1; "stop" -> 0xB2; "volup" -> 0xAF; "voldown" -> 0xAE; "mute" -> 0xAD; else -> return false }
        return runCatching { repeat(times) { Win32.keyTap(vk) }; true }.getOrDefault(false)
    }

    /** (percent, charging) or null on a PC without a battery. */
    fun battery(): Pair<Int, Boolean>? = if (!Paths.isWindows) null else runCatching {
        val s = Win32.powerStatus()
        val pct = s.BatteryLifePercent.toInt() and 255
        if (pct == 255 || (s.BatteryFlag.toInt() and 128) != 0) null else pct to ((s.ACLineStatus.toInt() and 255) == 1)
    }.getOrNull()

    fun online(): Boolean = runCatching { java.net.NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements() } }.getOrDefault(false)
}
