package app.belle.desktop

import app.belle.assistant.core.Platform
import app.belle.assistant.llm.LlamaEngine
import app.belle.assistant.threed.Ort
import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.NameArt
import app.belle.desktop.accel.Backend
import app.belle.desktop.accel.HardwareProbe
import java.io.File

/**
 * `MilkyMoosAssistant --selftest <report.txt>`: loads every native part of the installed program (ONNX Runtime and the
 * 3D runner, llama.cpp, sherpa-onnx speech, Skia drawing), checks HTTPS and the data folders, writes what worked to the
 * report and exits (0 = all passed, 1 = something failed). No window opens and no model is needed, so CI runs it on
 * the packaged program, and a user can run it to send a report.
 *
 * `--selftest <report.txt> --gpu`: the same with the backends chosen in Settings › This PC (after their downloads):
 * checks the GPU ONNX Runtime has its provider and opens the smallest installed 3D model on it without falling back,
 * and loads the chosen thinking model with the CUDA / Vulkan chat build, saying how many layers went to the GPU and
 * how fast it answers.
 */
object SelfTest {
    fun run(report: File, gpu: Boolean = false): Int {
        Platform.desktop = true
        val lines = ArrayList<String>(); var failed = 0
        fun check(name: String, body: () -> String) {
            val r = runCatching(body)
            lines += if (r.isSuccess) "PASS $name: ${r.getOrNull()}" else { failed++; "FAIL $name: ${r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}" }
        }
        lines += "Milky Moo's Assistant ${BuildInfo.VERSION} (${BuildInfo.GIT_SHA}) on ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}"
        lines += "natives: ${Natives.bundled.absolutePath}"
        check("hardware probe") { HardwareProbe.get().summary() }
        if (!gpu) check("native libraries (CPU set)") { Natives.install(Backend.CPU, Backend.CPU); "loaded" }
        else {
            val hw = HardwareProbe.get()
            val photo = app.belle.desktop.accel.BackendChoice.photo3D(DesktopPrefs.photoBackend, hw, RuntimePacks::photoReady)
            val chat = app.belle.desktop.accel.BackendChoice.chat(DesktopPrefs.chatBackend, hw, RuntimePacks::chatReady)
            lines += "chosen: Photo → 3D ${DesktopPrefs.photoBackend.label} → ${photo.backend.label} (${photo.why}); chat ${DesktopPrefs.chatBackend.label} → ${chat.backend.label} (${chat.why})"
            check("native libraries (${photo.backend.label} / ${chat.backend.label})") { Natives.install(photo.backend, chat.backend); "loaded" }
            if (photo.backend != Backend.CPU) check("Photo → 3D on ${photo.backend.label}") {
                val want = mapOf(Backend.CUDA to "CUDAExecutionProvider", Backend.DIRECTML to "DmlExecutionProvider", Backend.OPENVINO to "OpenVINOExecutionProvider")[photo.backend]
                val p = Ort.providers().toList(); require(want in p) { "the GPU ONNX Runtime has no $want (has $p)" }
                val m = app.belle.assistant.threed.Port3D.dir(DesktopContext).listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().minByOrNull { it.length() + File(it.path + ".data").length() }
                    ?: return@check "$want present (no 3D model installed to open)"
                var fell: String? = null; val was = Ort.onFallback; Ort.onFallback = { fell = it }
                try { val t = System.nanoTime(); Ort.Session(m.path).close(); require(fell == null) { fell!! }; "$want opened ${m.name} in ${(System.nanoTime() - t) / 1_000_000} ms" }
                finally { Ort.onFallback = was }
            }
            if (chat.backend != Backend.CPU) check("chat on ${chat.backend.label}") {
                val f = app.belle.assistant.Prefs(DesktopContext).llmFile.takeIf { it.isNotEmpty() }?.let { File(app.belle.assistant.models.ModelFiles.llmDir(DesktopContext), it) }?.takeIf { it.exists() } ?: app.belle.assistant.models.ModelFiles.installedLlms(DesktopContext).minByOrNull { it.length() }
                    ?: return@check "chat build loaded (no thinking model chosen to try)"
                val e = LlamaEngine(); require(e.load(f, 4, 2048)) { "couldn't load ${f.name}" }
                val layers = e.gpuLayers(); val t = System.nanoTime(); val out = e.complete("Answer in one short sentence.", "What is the capital of France?", "", 32)
                val ms = (System.nanoTime() - t) / 1_000_000; e.unload()
                require(layers > 0) { "the model loaded on the CPU (no room on the GPU?)" }
                "${f.name}: $layers layers on the GPU, answered in $ms ms: ${out.trim().take(60)}"
            }
        }
        check("ONNX Runtime + 3D runner") { val p = Ort.providers().toList(); require("CPUExecutionProvider" in p) { "providers $p" }; p.joinToString(", ") }
        check("llama.cpp chat engine") { LlamaEngine(); "loaded" }
        check("sherpa-onnx speech") { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); "loaded" }
        check("Skia drawing (name art)") {
            val b = AssistantWidget.trim(NameArt.render(DesktopContext, "Self Test", NameArt.STYLES.first().first, "sans", 400, 40f, phase = 0.3f, maxLines = 1))
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val n = px.count { (it ushr 24) > 40 }; require(n > 200) { "only $n visible pixels" }; "$n pixels"
        }
        check("Bundled assets") { BundledAssets.verify(DesktopContext.assets) }
        check("bundled pictures (drawables)") {
            app.belle.assistant.R.drawable.all.forEach { (id, path) -> requireNotNull(SelfTest::class.java.getResource("/$path")) { "missing $path" }; if (!path.endsWith(".xml")) requireNotNull(android.graphics.BitmapFactory.decodeResource(DesktopContext.resources, id)) { "can't decode $path" } }
            "${app.belle.assistant.R.drawable.all.size} drawables"
        }
        check("Photo → 3D model list") { val m = app.belle.assistant.threed.Pixal3DInstall.manifest(DesktopContext); require(m.files.isNotEmpty()) { "no files" }; "${m.version}: ${m.files.size} files" }
        // the start-up path that failed on 0.1.5 ("Speech models failed to load"): bundled wake-word model → sherpa-onnx spotter
        check("wake word (bundled model)") {
            app.belle.assistant.models.ModelFiles.ensureBundled(DesktopContext)
            val w = app.belle.assistant.speech.WakeWordEngine(DesktopContext, app.belle.assistant.Prefs(DesktopContext))
            try { w.accept(FloatArray(16000)); "listening for ${w.phrases.size} phrases" } finally { w.release() }
        }
        check("background cut-out model (bundled)") {
            val f = File.createTempFile("selftest-u2netp", ".onnx")
            try { DesktopContext.assets.open("models/u2netp.onnx").use { i -> f.outputStream().use { i.copyTo(it) } }; Ort.Session(f.path).close(); "opened (${f.length() / 1000} KB)" } finally { f.delete() }
        }
        check("HTTPS (downloads)") {
            val suites = javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.cipherSuites
            require(suites.any { it.contains("ECDHE") }) { "no ECDHE cipher suites (jdk.crypto.ec missing?)" }; "${suites.size} cipher suites"
        }
        check("data folders") {
            listOf(Paths.config, Paths.data).forEach { d -> d.mkdirs(); val t = File(d, ".selftest"); t.writeText("ok"); require(t.delete()) { "can't write $d" } }
            "config ${Paths.config}, data ${Paths.data}"
        }
        runCatching { app.belle.assistant.ProfileStore.pending(DesktopContext) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { lines += "default assistants: still to import $it (retried at the next start)" }
        lines += if (failed == 0) "RESULT: PASS" else "RESULT: FAIL ($failed)"
        runCatching { report.absoluteFile.parentFile?.mkdirs(); report.writeText(lines.joinToString("\n") + "\n") }
        lines.forEach(::println)
        return if (failed == 0) 0 else 1
    }
}
