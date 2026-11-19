package app.belle.assistant.core

/**
 * Which device family this build runs on: the phone app, or the Windows / Linux desktop program (which sets
 * [desktop] at startup). Shared screens use it to leave out phone-only settings (Android permissions, the home-screen
 * bubble, battery optimisation) and to say "PC" instead of "phone".
 */
object Platform {
    @Volatile var desktop = false
    /** "phone" or "PC", for sentences like "stays on this phone". */
    val device: String get() = if (desktop) "PC" else "phone"
    /** "tap" on the phone, "click" on the PC, for instructions in shared screens. */
    val tap: String get() = if (desktop) "click" else "tap"
    val tapCap: String get() = tap.replaceFirstChar { it.uppercase() }
    val oneTap: String get() = if (desktop) "one-click" else "one-tap"
    val oneTapCap: String get() = oneTap.replaceFirstChar { it.uppercase() }
    /** One line about the machine for build logs, set by the desktop program ("PC: Windows 11 · RTX 5070 Ti …"); null on the phone. */
    @Volatile var describe: (() -> String)? = null
    /** Whose light / dark setting "follow" means: "phone" or "Windows". */
    val system: String get() = if (!desktop) "phone" else if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "Windows" else "system"
}
