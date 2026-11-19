package app.belle.desktop

import java.io.File

/**
 * Where the desktop app keeps things (the AGENTS.md §5 mapping):
 * settings  → %APPDATA%\MilkyMoo\profiles (Linux: $XDG_CONFIG_HOME/MilkyMoo/profiles)
 * app files → %LOCALAPPDATA%\MilkyMoo (models, avatars, checkpoints, logs; Linux: $XDG_DATA_HOME/MilkyMoo)
 * `-Dbelle.home=<dir>` or BELLE_HOME (tests, CI, portable runs) puts both under one folder.
 */
object Paths {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val home: File? = (System.getProperty("belle.home") ?: System.getenv("BELLE_HOME"))?.let(::File)   // tests, CI and portable use

    val config: File by lazy {
        home?.let { File(it, "config") } ?: if (isWindows) File(System.getenv("APPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Roaming", "MilkyMoo")
        else File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "MilkyMoo")
    }
    val data: File by lazy {
        home?.let { File(it, "data") } ?: if (isWindows) File(System.getenv("LOCALAPPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Local", "MilkyMoo")
        else File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "MilkyMoo")
    }
    val profiles get() = File(config, "profiles").apply { mkdirs() }
    val files get() = File(data, "files").apply { mkdirs() }
    val cache get() = File(data, "cache").apply { mkdirs() }
    val logs get() = File(data, "logs").apply { mkdirs() }
    /** Native runtimes downloaded by setup (CUDA / Vulkan builds of llama.cpp, ONNX Runtime with GPU providers). */
    val runtimes get() = File(data, "runtimes").apply { mkdirs() }
}
