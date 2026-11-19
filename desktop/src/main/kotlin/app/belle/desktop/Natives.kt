package app.belle.desktop

import app.belle.assistant.core.NativeLibs
import app.belle.desktop.accel.Backend
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * Loads the native libraries for the chosen backends. The installer carries the CPU set (onnxruntime 1.28.2 and
 * sherpa-onnx from the sherpa-onnx 1.13.8 Windows build, belle_llm in CPU / CUDA / Vulkan builds, belle_ort); the GPU
 * runtimes (ONNX Runtime GPU builds, NVIDIA's CUDA libraries) are downloaded by Setup into runtimes/<id>/ ([RuntimePacks]). The backend is fixed for the life of the process (a different onnxruntime can't be swapped
 * in once speech has loaded one), so changing it in Settings applies after a restart; Settings says so.
 */
object Natives {
    /** The CPU set shipped with the program. */
    val bundled: File by lazy {
        System.getProperty("belle.natives")?.let(::File)
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "natives") }?.takeIf { it.isDirectory }
            ?: File("desktop/build/natives").absoluteFile
    }
    fun runtimeDir(id: String) = File(Paths.runtimes, id)
    /** Runtime packs (folders under runtimes/) per backend. */
    fun ortPack(b: Backend) = when (b) { Backend.CUDA -> "ort-cuda"; Backend.DIRECTML -> "ort-dml"; Backend.OPENVINO -> "ort-openvino"; else -> null }
    /** The chat (llama.cpp) builds for CUDA and Vulkan ship with the program, next to the CPU one. */
    fun llmDir(b: Backend) = when (b) { Backend.CUDA -> File(bundled, "llm-cuda"); Backend.VULKAN -> File(bundled, "llm-vulkan"); else -> null }
    /** CUDA's own DLLs (cudart, cuBLAS, cuDNN) shared by both CUDA packs. */
    const val CUDA_PACK = "cuda-runtime"

    @Volatile var photoBackend = Backend.CPU; private set
    @Volatile var chatBackend = Backend.CPU; private set
    private val loaded = HashSet<String>()

    private fun libFile(dir: File, name: String) = File(dir, System.mapLibraryName(name))

    private interface Kernel32 : StdCallLibrary { fun SetEnvironmentVariableW(n: WString, v: WString?): Boolean }
    /** ONNX Runtime's provider name for a backend. */
    fun provider(b: Backend) = when (b) { Backend.CUDA -> "cuda"; Backend.DIRECTML -> "dml"; Backend.OPENVINO -> "openvino"; else -> "cpu" }

    /**
     * Must run before anything touches speech, chat or 3D. Speech always uses the bundled CPU onnxruntime; the 3D
     * builder's GPU onnxruntime (if any) is handed to belle_ort by full path (BELLE_ORT_DLL), so the two never clash.
     */
    fun install(photo: Backend, chat: Backend) {
        photoBackend = photo; chatBackend = chat
        val ort = ortPack(photo)?.let(::runtimeDir)?.let { libFile(it, "onnxruntime") }?.takeIf { it.exists() }
        val packs = (listOfNotNull(llmDir(chat)) + listOfNotNull(if (photo == Backend.CUDA || chat == Backend.CUDA) runtimeDir(CUDA_PACK) else null))
            .filter { it.isDirectory } + listOfNotNull(ort?.parentFile)
        if (Paths.isWindows) {
            // dependent DLLs (cudart, cuBLAS, cuDNN, DirectML) are found through PATH: put the packs and the bundled set first
            val k = Native.load("kernel32", Kernel32::class.java)
            val path = (packs + bundled).joinToString(";") { it.absolutePath } + ";" + (System.getenv("PATH") ?: "")
            k.SetEnvironmentVariableW(WString("PATH"), WString(path))
            k.SetEnvironmentVariableW(WString("BELLE_ORT_DLL"), ort?.let { WString(it.absolutePath) })
        }
        System.setProperty("java.library.path", (listOf(bundled) + packs).joinToString(File.pathSeparator) { it.absolutePath })
        NativeLibs.load = { name -> load(name) }
        // sherpa-onnx (speech) links against "onnxruntime" by name: load the bundled one first so it gets that one
        load("onnxruntime")
        if (photo != Backend.CPU && ort != null) {
            app.belle.assistant.threed.Ort.useProvider(provider(photo))
            app.belle.assistant.threed.Ort.onFallback = { msg -> android.util.Log.w("Natives", msg); app.belle.assistant.threed.Pixal3DJob.note(DesktopContext, msg) }
        }
        android.util.Log.i("Natives", "photo→3D: ${photo.label}, chat: ${chat.label}, packs: ${packs.joinToString { it.name }.ifEmpty { "none" }}")
    }

    @Synchronized fun load(name: String) {
        if (name in loaded) return
        val dirs = when (name) {
            "onnxruntime" -> listOf(bundled)
            "belle_llm" -> listOfNotNull(llmDir(chatBackend), bundled)
            else -> listOf(bundled)
        }
        val f = dirs.map { libFile(it, name) }.firstOrNull { it.exists() }
            ?: dirs.flatMap { d -> d.listFiles { x -> x.name.startsWith(System.mapLibraryName(name)) }.orEmpty().toList() }.firstOrNull()   // libonnxruntime.so.1.x
        if (f == null) System.loadLibrary(name) else System.load(f.absolutePath)
        loaded += name
    }
}
