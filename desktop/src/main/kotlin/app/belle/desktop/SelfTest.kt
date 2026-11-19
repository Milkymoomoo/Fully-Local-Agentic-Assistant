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
 */
object SelfTest {
    fun run(report: File): Int {
        Platform.desktop = true
        val lines = ArrayList<String>(); var failed = 0
        fun check(name: String, body: () -> String) {
            val r = runCatching(body)
            lines += if (r.isSuccess) "PASS $name: ${r.getOrNull()}" else { failed++; "FAIL $name: ${r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}" }
        }
        lines += "Milky Moo's Assistant ${BuildInfo.VERSION} (${BuildInfo.GIT_SHA}) on ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}"
        lines += "natives: ${Natives.bundled.absolutePath}"
        check("hardware probe") { HardwareProbe.get().summary() }
        check("native libraries (CPU set)") { Natives.install(Backend.CPU, Backend.CPU); "loaded" }
        check("ONNX Runtime + 3D runner") { val p = Ort.providers().toList(); require("CPUExecutionProvider" in p) { "providers $p" }; p.joinToString(", ") }
        check("llama.cpp chat engine") { LlamaEngine(); "loaded" }
        check("sherpa-onnx speech") { Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); "loaded" }
        check("Skia drawing (name art)") {
            val b = AssistantWidget.trim(NameArt.render(DesktopContext, "Self Test", NameArt.STYLES.first().first, "sans", 400, 40f, phase = 0.3f, maxLines = 1))
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val n = px.count { (it ushr 24) > 40 }; require(n > 200) { "only $n visible pixels" }; "$n pixels"
        }
        check("HTTPS (downloads)") {
            val suites = javax.net.ssl.SSLContext.getDefault().supportedSSLParameters.cipherSuites
            require(suites.any { it.contains("ECDHE") }) { "no ECDHE cipher suites (jdk.crypto.ec missing?)" }; "${suites.size} cipher suites"
        }
        check("data folders") {
            listOf(Paths.config, Paths.data).forEach { d -> d.mkdirs(); val t = File(d, ".selftest"); t.writeText("ok"); require(t.delete()) { "can't write $d" } }
            "config ${Paths.config}, data ${Paths.data}"
        }
        lines += if (failed == 0) "RESULT: PASS" else "RESULT: FAIL ($failed)"
        runCatching { report.absoluteFile.parentFile?.mkdirs(); report.writeText(lines.joinToString("\n") + "\n") }
        lines.forEach(::println)
        return if (failed == 0) 0 else 1
    }
}
