package app.belle.desktop

import app.belle.assistant.llm.LlamaEngine
import app.belle.assistant.models.ModelFiles
import app.belle.assistant.speech.SAMPLE_RATE
import app.belle.assistant.speech.SpeechToText
import app.belle.assistant.speech.TextToSpeech
import app.belle.desktop.accel.Backend
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The shared speech and chat code running on the desktop natives (needs BELLE_NATIVES and the model folders
 * passed as -Ddesk.models=<dir with stt/voice/vad> and -Ddesk.gguf=<file>; skipped otherwise).
 * Round trip: Piper speaks a sentence, Moonshine (+ Silero VAD) transcribes it back; llama.cpp answers a question.
 */
class SpeechChatTest {
    companion object {
        val ctx get() = DesktopContext
        @BeforeClass @JvmStatic fun setup() {
            assumeTrue("BELLE_NATIVES not set", System.getProperty("belle.natives") != null)
            TestHome.use()
            Natives.install(Backend.CPU, Backend.CPU)
            val m = System.getProperty("desk.models")?.let(::File) ?: return
            val root = ModelFiles.root(ctx)
            // a link where the OS allows it (Windows needs developer mode for symlinks), a copy otherwise
            fun link(src: File, dst: File) { if (!dst.exists()) runCatching { java.nio.file.Files.createSymbolicLink(dst.toPath(), src.absoluteFile.toPath()) }.onFailure { src.copyRecursively(dst) } }
            link(File(m, "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"), File(root, ModelFiles.STT_DIR))
            link(File(m, "silero_vad.onnx"), ModelFiles.vad(ctx))
            link(File(m, "vits-piper-en_US-amy-medium-int8"), File(ModelFiles.voicesDir(ctx), "vits-piper-en_US-amy-medium-int8"))
        }
    }

    @Test fun speakThenHear() {
        assumeTrue(ModelFiles.hasStt(ctx))
        val tts = TextToSpeech(ctx, "vits-piper-en_US-amy-medium-int8")
        val t0 = System.nanoTime()
        val (pcm, rate) = tts.synth("The weather today is sunny and warm.", 0, 1.0f)
        println("tts ${tts.engine}: ${pcm.size} samples @ $rate Hz in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(pcm.size > rate)
        val n = (pcm.size.toLong() * SAMPLE_RATE / rate).toInt()
        val x = FloatArray(n) { i -> val p = i.toDouble() * rate / SAMPLE_RATE; val a = p.toInt().coerceAtMost(pcm.size - 1); val b = (a + 1).coerceAtMost(pcm.size - 1); (pcm[a] + (pcm[b] - pcm[a]) * (p - a)).toFloat() }
        val stt = SpeechToText(ctx)
        val text = stt.transcribe(x)
        println("stt: '$text'")
        assertTrue(text.lowercase().contains("weather") && text.lowercase().contains("sunny"), text)
        stt.release(); tts.release()
    }

    @Test fun chatAnswers() {
        val gguf = System.getProperty("desk.gguf")?.let(::File)
        assumeTrue(gguf?.exists() == true)
        val llm = LlamaEngine()
        assertTrue(llm.load(gguf!!, 4, 2048))
        val t0 = System.nanoTime()
        val out = llm.complete("You are a helpful assistant. Answer in one short sentence.", "What is the capital of France?", "", 32)
        println("llm: '$out' in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(out.contains("Paris"), out)
        llm.unload()
    }
}
