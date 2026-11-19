package app.belle.desktop

import app.belle.assistant.models.Fetcher
import app.belle.assistant.threed.ModelInstaller
import app.belle.assistant.threed.ModelManifest
import app.belle.desktop.accel.Backend
import java.io.File
import java.util.zip.ZipFile

/**
 * The optional GPU runtimes (and FFmpeg), downloaded on demand from their publishers — NuGet (Microsoft's and Intel's
 * ONNX Runtime builds, DirectML), NVIDIA (CUDA runtime, cuBLAS, cuFFT, cuDNN) and FFmpeg's Windows builds — never
 * re-hosted by us. Each download is pinned to an exact file, size and SHA-256 and goes through the same resumable,
 * checked [ModelInstaller] the 3D model set uses (per-file progress, retry, resume after a restart). When every
 * archive of a pack is in, the DLLs it needs are unpacked into runtimes/<id>/ and the archives deleted.
 */
object RuntimePacks {
    class Archive(val name: String, val url: String, val size: Long, val sha256: String, val take: Regex)
    class Pack(val id: String, val label: String, val what: String, val version: String, val license: String, val licenseUrl: String,
               val archives: List<Archive>) { val bytes get() = archives.sumOf { it.size } }

    private const val NUGET = "https://api.nuget.org/v3-flatcontainer"
    private const val NV = "https://developer.download.nvidia.com/compute"

    val ORT_CUDA = Pack("ort-cuda", "ONNX Runtime for NVIDIA (CUDA)", "Runs Photo → 3D on an NVIDIA GPU. Needs the NVIDIA CUDA runtime too.", "1.32.0",
        "MIT (Microsoft ONNX Runtime)", "https://www.nuget.org/packages/Microsoft.ML.OnnxRuntime.Gpu.Windows/1.32.0",
        listOf(Archive("microsoft.ml.onnxruntime.gpu.windows.1.32.0.nupkg", "$NUGET/microsoft.ml.onnxruntime.gpu.windows/1.32.0/microsoft.ml.onnxruntime.gpu.windows.1.32.0.nupkg",
            155209053, "485c1af4e5719d7336c2f1cdf6d3342a3fcec88d3bad9621e33a283fff77c4c7", Regex("runtimes/win-x64/native/(onnxruntime|onnxruntime_providers_cuda|onnxruntime_providers_shared)\\.dll"))))
    val CUDA = Pack(Natives.CUDA_PACK, "NVIDIA CUDA 13 runtime + cuDNN 9", "NVIDIA's own GPU libraries, used by both Photo → 3D and chat on CUDA. Needs an NVIDIA driver from 580 or newer.", "13.5 / cuDNN 9.28.1",
        "NVIDIA CUDA Toolkit EULA and cuDNN Software License Agreement (redistributable runtime files, downloaded straight from NVIDIA)", "https://docs.nvidia.com/cuda/eula/index.html",
        listOf(
            Archive("cuda_cudart-windows-x86_64-13.5.37-archive.zip", "$NV/cuda/redist/cuda_cudart/windows-x86_64/cuda_cudart-windows-x86_64-13.5.37-archive.zip",
                2742330, "ef9747b13bd2c2c199a5f2756b56c94d0b5acf04556ce95ef4649122ee9b752a", Regex(".*/bin/(x64/)?cudart64_\\d+\\.dll")),
            Archive("libcublas-windows-x86_64-13.10.0.20-archive.zip", "$NV/cuda/redist/libcublas/windows-x86_64/libcublas-windows-x86_64-13.10.0.20-archive.zip",
                426404439, "468d90ff5680fd08fd9130e6e443c6e0c934f2f98b6ae4189960b9d9039e8969", Regex(".*/bin/(x64/)?cublas(Lt)?64_\\d+\\.dll")),
            Archive("libcufft-windows-x86_64-12.5.0.14-archive.zip", "$NV/cuda/redist/libcufft/windows-x86_64/libcufft-windows-x86_64-12.5.0.14-archive.zip",
                163129974, "b4d06d735b8565f9eb1c5ff156078e8c240eb025a158f951a99cc0cc04712f3b", Regex(".*/bin/(x64/)?cufft64_\\d+\\.dll")),
            Archive("cudnn-windows-x86_64-9.28.1.4_cuda13-archive.zip", "$NV/cudnn/redist/cudnn/windows-x86_64/cudnn-windows-x86_64-9.28.1.4_cuda13-archive.zip",
                1299382450, "5963a8776803a2c3772a5b71ecbb27cba81977321612f5d794c0468af4e5e66b", Regex(".*/bin/(x64/)?cudnn[a-z_]*64_9\\.dll"))))
    val ORT_DML = Pack("ort-dml", "ONNX Runtime for DirectML (AMD / Intel / any DirectX 12 GPU)", "Untested: runs Photo → 3D through DirectX 12. Nobody has tried it on an AMD or Intel GPU yet.", "1.24.4 / DirectML 1.15.4",
        "MIT (Microsoft ONNX Runtime); DirectML redistributable licence (Microsoft)", "https://www.nuget.org/packages/Microsoft.AI.DirectML/1.15.4",
        listOf(
            Archive("microsoft.ml.onnxruntime.directml.1.24.4.nupkg", "$NUGET/microsoft.ml.onnxruntime.directml/1.24.4/microsoft.ml.onnxruntime.directml.1.24.4.nupkg",
                12458649, "57e9f11b73437bef7a309496135d4c1f96b1a8e9ddba60013fa27bfc1d788681", Regex("runtimes/win-x64/native/(onnxruntime|onnxruntime_providers_shared)\\.dll")),
            Archive("microsoft.ai.directml.1.15.4.nupkg", "$NUGET/microsoft.ai.directml/1.15.4/microsoft.ai.directml.1.15.4.nupkg",
                202292617, "4e7cb7ddce8cf837a7a75dc029209b520ca0101470fcdf275c1f49736a3615b9", Regex("bin/x64-win/DirectML\\.dll"))))
    val ORT_OPENVINO = Pack("ort-openvino", "ONNX Runtime for Intel OpenVINO", "Untested: runs Photo → 3D on Intel Arc / Iris Xe graphics. Nobody has tried it yet.", "1.24.1",
        "MIT (ONNX Runtime); Apache-2.0 (Intel OpenVINO)", "https://www.nuget.org/packages/Intel.ML.OnnxRuntime.OpenVino/1.24.1",
        listOf(Archive("intel.ml.onnxruntime.openvino.1.24.1.nupkg", "$NUGET/intel.ml.onnxruntime.openvino/1.24.1/intel.ml.onnxruntime.openvino.1.24.1.nupkg",
            43011684, "f53ad5f90e3d616970a5c65e4880ebbe92c9774e9727020661db591cea74a110",
            Regex("runtimes/win-x64/native/(?![^/]*_debug)(?![^/]*(tensorflow|paddle|pytorch))[^/]+\\.dll"))))
    val FFMPEG = Pack("ffmpeg", "FFmpeg (video backgrounds)", "Lets a video play as the avatar's background, with its sound. Optional.", "9.0.2 (gyan.dev essentials build)",
        "GPL-3.0 (FFmpeg essentials build by gyan.dev; a separate program the assistant runs)", "https://www.gyan.dev/ffmpeg/builds/",
        listOf(Archive("ffmpeg-9.0.2-essentials_build.zip", "https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip",
            114768076, "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba", Regex(".*/bin/(ffmpeg|ffprobe)\\.exe"))))

    val ALL = listOf(CUDA, ORT_CUDA, ORT_DML, ORT_OPENVINO, FFMPEG)
    fun byId(id: String) = ALL.first { it.id == id }

    /** Download hosts the packs may use (first hop) and where those hosts redirect to. */
    private val FIRST = setOf("api.nuget.org", "developer.download.nvidia.com", "github.com")
    private val REDIRECT = listOf(".nuget.org", ".nvidia.com", ".githubusercontent.com", ".akamaized.net", ".azureedge.net")
    private val fetcher = Fetcher(hostOk = { u, first -> u.protocol == "https" && (u.host in FIRST || (!first && REDIRECT.any { u.host.endsWith(it) })) })

    fun dir(p: Pack) = Natives.runtimeDir(p.id)
    private fun stage(p: Pack) = File(Paths.runtimes, ".dl/${p.id}")
    private fun readyFile(p: Pack) = File(dir(p), ".ready")
    fun ready(p: Pack) = readyFile(p).takeIf { it.exists() }?.readText()?.trim() == p.version

    /** The chat build for CUDA / Vulkan ships inside the program (natives/llm-cuda, natives/llm-vulkan). */
    fun llmBundled(b: Backend) = Natives.llmDir(b)?.let { File(it, System.mapLibraryName("belle_llm")).exists() } ?: false

    /** For [app.belle.desktop.accel.BackendChoice]: is everything this backend needs on disk? */
    fun installed(b: Backend): Boolean = when (b) {
        Backend.CPU, Backend.AUTO -> true
        Backend.CUDA -> ready(CUDA) && (ready(ORT_CUDA) || llmBundled(Backend.CUDA))
        Backend.DIRECTML -> ready(ORT_DML)
        Backend.OPENVINO -> ready(ORT_OPENVINO)
        Backend.VULKAN -> llmBundled(Backend.VULKAN)
        Backend.ROCM -> false
    }
    fun photoReady(b: Backend) = when (b) { Backend.CUDA -> ready(CUDA) && ready(ORT_CUDA); Backend.CPU -> true; else -> installed(b) }
    fun chatReady(b: Backend) = when (b) { Backend.CUDA -> ready(CUDA) && llmBundled(Backend.CUDA); Backend.CPU -> true; else -> installed(b) }
    /** The packs a backend needs (in download order). */
    fun needs(b: Backend, photo: Boolean): List<Pack> = when (b) {
        Backend.CUDA -> if (photo) listOf(CUDA, ORT_CUDA) else listOf(CUDA)
        Backend.DIRECTML -> listOf(ORT_DML)
        Backend.OPENVINO -> listOf(ORT_OPENVINO)
        else -> emptyList()
    }

    private val installers = HashMap<String, ModelInstaller>()
    private val unpacking = HashMap<String, String>()   // pack id → status line while unpacking / last unpack error
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    fun installer(p: Pack): ModelInstaller = synchronized(installers) {
        installers.getOrPut(p.id) {
            val m = ModelManifest(p.version, p.licenseUrl, p.archives.map { a -> ModelManifest.Entry(a.name, a.name, a.size, a.sha256, p.license, a.url, emptyList()) }, emptyList())
            ModelInstaller(stage(p), m, fetcher).also { i -> i.addListener { onChange(p, i) } }
        }
    }
    fun unpackNote(p: Pack): String? = synchronized(unpacking) { unpacking[p.id] }

    fun install(p: Pack) { if (!ready(p)) { app.belle.assistant.models.DownloadService.started(DesktopContext); installer(p).installAll(); held += p.id } }
    fun retry(p: Pack, file: String) { held += p.id; installer(p).retry(file) }
    fun cancel(p: Pack) = installer(p).cancel()
    fun remove(p: Pack) { cancel(p); dir(p).deleteRecursively(); stage(p).deleteRecursively(); synchronized(installers) { installers.remove(p.id) }; changed() }
    /** At start: carry on with a pack download that was running when the program closed. */
    fun resumeAll() = ALL.filter { !ready(it) && File(stage(it), ".install_wanted").exists() }.forEach { install(it) }
    private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun onChange(p: Pack, i: ModelInstaller) {
        if (!i.busy && i.manifest.files.all { i.installed(it) } && !ready(p)) {
            synchronized(unpacking) { if (unpacking[p.id] == "Unpacking…") return; unpacking[p.id] = "Unpacking…" }
            changed()
            Thread({
                val err = runCatching { unpack(p) }.exceptionOrNull()
                synchronized(unpacking) { if (err == null) unpacking.remove(p.id) else unpacking[p.id] = "Couldn't unpack: ${err.message}" }
                release(p); changed()
            }, "unpack-${p.id}").start()
        } else {
            if (!i.busy && held.contains(p.id)) release(p)
            changed()
        }
    }
    private fun release(p: Pack) { if (held.remove(p.id)) app.belle.assistant.models.DownloadService.finished(DesktopContext) }

    /** Takes the DLLs each archive contributes, flat, into runtimes/<id>/, then marks the pack ready and frees the archives' space. */
    fun unpack(p: Pack) {
        val out = dir(p); val tmp = File(out.parentFile, "${p.id}.new").apply { deleteRecursively(); mkdirs() }
        for (a in p.archives) {
            var n = 0
            ZipFile(File(stage(p), a.name)).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || !a.take.matches(e.name)) continue
                    z.getInputStream(e).use { inp -> File(tmp, e.name.substringAfterLast('/')).outputStream().use { inp.copyTo(it, 1 shl 20) } }
                    n++
                }
            }
            check(n > 0) { "${a.name} has none of the expected files" }
        }
        File(tmp, ".ready").writeText(p.version)
        out.deleteRecursively()
        check(tmp.renameTo(out)) { "couldn't move the files into ${out.path}" }
        stage(p).deleteRecursively()
        synchronized(installers) { installers.remove(p.id) }
        android.util.Log.i("RuntimePacks", "${p.id} ${p.version} ready (${out.list()?.size} files)")
    }
}
