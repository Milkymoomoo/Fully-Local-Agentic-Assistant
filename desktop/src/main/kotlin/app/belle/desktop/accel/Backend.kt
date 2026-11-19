package app.belle.desktop.accel

/**
 * Acceleration backends. [tested] marks the supported paths (CPU everywhere, NVIDIA CUDA as the main GPU target);
 * the others are built and wired the same way, but nobody has run them on AMD / Intel hardware yet, and Settings and
 * docs/features/windows.md say so next to each choice. Auto only ever picks a supported path.
 */
enum class Backend(val id: String, val label: String, val tested: Boolean, val note: String) {
    AUTO("auto", "Auto", true, "NVIDIA CUDA when an NVIDIA card is found, otherwise CPU. Untested GPU paths are never picked on their own."),
    CPU("cpu", "CPU only", true, "Works on every PC. Uses plain RAM; slowest."),
    CUDA("cuda", "NVIDIA CUDA", true, "NVIDIA GeForce RTX 20-series or newer (the main GPU target)."),
    DIRECTML("dml", "DirectML (AMD / Intel / any DirectX 12 GPU)", false, "Untested: nobody has run this on an AMD or Intel GPU yet."),
    OPENVINO("openvino", "Intel OpenVINO", false, "Untested: for Intel Arc / Iris Xe graphics; nobody has run it yet."),
    ROCM("rocm", "AMD ROCm", false, "Untested, and not offered on Windows: ONNX Runtime only ships ROCm for Linux. On Windows use DirectML for AMD."),
    VULKAN("vulkan", "Vulkan (AMD / Intel / NVIDIA)", false, "Untested on AMD / Intel. Chat only: ONNX Runtime has no Vulkan path, so Photo → 3D falls back to CPU.");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
        /** What each part of the app can use. Photo → 3D runs on ONNX Runtime; chat on llama.cpp. */
        val forPhoto3D = listOf(AUTO, CPU, CUDA, DIRECTML, OPENVINO, ROCM)
        val forChat = listOf(AUTO, CPU, CUDA, VULKAN)
    }
}

/** What the PC has, as far as cheap checks can tell (no GPU code is run to find out). */
data class Hardware(val nvidia: Boolean, val gpuNames: List<String>, val vramMb: Long, val vulkan: Boolean, val dx12: Boolean, val ramMb: Long, val cores: Int) {
    val amd get() = gpuNames.any { it.contains("AMD", true) || it.contains("Radeon", true) }
    val intelGpu get() = gpuNames.any { it.contains("Intel", true) && (it.contains("Arc", true) || it.contains("Iris", true) || it.contains("UHD", true)) }
    fun summary() = buildString {
        append(if (gpuNames.isEmpty()) "no GPU found" else gpuNames.joinToString(", "))
        if (vramMb > 0) append(" (${vramMb / 1024} GB VRAM)")
        append(" · ${ramMb / 1024} GB RAM · $cores CPU threads")
    }
}

/** Resolves Auto (and backends a part can't use) into what actually runs, with the reason shown in the build log. */
object BackendChoice {
    data class Resolved(val backend: Backend, val why: String)
    fun photo3D(want: Backend, hw: Hardware, installed: (Backend) -> Boolean): Resolved = when (want) {
        Backend.AUTO -> if (hw.nvidia && installed(Backend.CUDA)) Resolved(Backend.CUDA, "Auto: NVIDIA GPU found")
            else Resolved(Backend.CPU, if (hw.nvidia) "Auto: NVIDIA GPU found but its runtime isn't downloaded yet — CPU for now" else "Auto: no NVIDIA GPU — CPU")
        Backend.VULKAN -> Resolved(Backend.CPU, "Photo → 3D has no Vulkan path — CPU")
        Backend.ROCM -> Resolved(Backend.CPU, "ROCm isn't available for ONNX Runtime on Windows — CPU (try DirectML)")
        Backend.CPU -> Resolved(Backend.CPU, "CPU chosen")
        else -> if (installed(want)) Resolved(want, "${want.label} chosen") else Resolved(Backend.CPU, "${want.label} runtime not downloaded — CPU")
    }
    fun chat(want: Backend, hw: Hardware, installed: (Backend) -> Boolean): Resolved = when (want) {
        Backend.AUTO -> if (hw.nvidia && installed(Backend.CUDA)) Resolved(Backend.CUDA, "Auto: NVIDIA GPU found") else Resolved(Backend.CPU, "Auto: CPU")
        Backend.CUDA, Backend.VULKAN -> if (installed(want)) Resolved(want, "${want.label} chosen") else Resolved(Backend.CPU, "${want.label} runtime not downloaded — CPU")
        Backend.CPU -> Resolved(Backend.CPU, "CPU chosen")
        else -> Resolved(Backend.CPU, "${want.label} isn't offered for chat — CPU")
    }
}
