package app.belle.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.belle.assistant.Prefs
import app.belle.assistant.core.UnigramTokenizer
import app.belle.assistant.core.WakeWord
import app.belle.assistant.models.ModelFiles
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

private const val TAG = "BelleSpeech"
const val SAMPLE_RATE = 16000

/** Wake word: streaming keyword spotter (3.3M params), keywords built from the typed name. */
class WakeWordEngine(ctx: Context, prefs: Prefs) {
    private val spotter: KeywordSpotter
    private var stream: OnlineStream
    val phrases: List<String>
    val rejected: List<String>

    init {
        val dir = ModelFiles.kws(ctx)
        val tok = UnigramTokenizer(File(dir, "bpe.vocab").readLines())
        val rejected = mutableListOf<String>()
        // All profiles' wake words when "each assistant answers its own wake words" is on; else just the active one's.
        val listen = app.belle.assistant.core.ProfileRouter.listeningPhrases(
            app.belle.assistant.ProfileStore.wakeTable(ctx), app.belle.assistant.ProfileStore.activeId(ctx), app.belle.assistant.ProfileStore.multiWake(ctx))
        val lines = WakeWord.keywordLines(tok, listen.ifEmpty { prefs.wakePhrases }, prefs.sensitivity, rejected)
            .ifEmpty { WakeWord.keywordLines(tok, WakeWord.defaultPhrases(prefs.assistantName), prefs.sensitivity) }
        phrases = lines.map { it.substringAfterLast('@').replace('_', ' ') }.distinct()
        this.rejected = rejected
        val kwFile = File(ctx.filesDir, "keywords.txt").apply { writeText(lines.joinToString("\n") + "\n") }
        Log.i(TAG, "wake phrases: $phrases")
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = 1,
                provider = "cpu",
            ),
            maxActivePaths = 4,
            keywordsFile = kwFile.path,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(null, cfg)
        stream = spotter.createStream("")
    }

    /** Feed audio; returns the detected phrase or null. */
    @Volatile private var released = false
    /** Tokens of the last non-empty spotter result (sherpa-onnx's spotter exposes no per-phrase score). */
    @Volatile var lastTokens: String? = null

    /** Mic thread calls accept(); reset()/release() come from other threads — all serialized here. */
    @Synchronized fun accept(samples: FloatArray): String? {
        if (released) return null
        return acceptLocked(samples)
    }

    private fun acceptLocked(samples: FloatArray): String? {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        var hit: String? = null
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val r = spotter.getResult(stream)
            val kw = r.keyword
            if (r.tokens.isNotEmpty()) lastTokens = r.tokens.joinToString("").replace('▁', ' ').trim()
            if (kw.isNotEmpty()) { hit = kw; spotter.reset(stream) }
        }
        return hit
    }

    @Synchronized fun reset() { if (released) return; stream.release(); stream = spotter.createStream("") }
    @Synchronized fun release() { if (released) return; released = true; stream.release(); spotter.release() }
}

/** Speech-to-text: Silero VAD finds the utterance, Moonshine Tiny (v2, int8) transcribes it. */
class SpeechToText(ctx: Context) {
    private val recognizer: OfflineRecognizer
    private val vad: Vad

    init {
        check(ModelFiles.hasStt(ctx)) { "Speech recognition pack not installed yet. Add it in Settings › Speech recognition." }
        val d = ModelFiles.stt(ctx)
        recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = File(d, "encoder_model.ort").path,
                    mergedDecoder = File(d, "decoder_model_merged.ort").path,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = 2,
            ),
        ))
        vad = Vad(null, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelFiles.vad(ctx).path, threshold = 0.5f,
                minSilenceDuration = 0.7f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE, numThreads = 1,
        ))
    }

    private val window = FloatArray(512)
    private var filled = 0
    var heardSpeech = false
        private set
    /** Samples of continuous speech in the current utterance (for the hard length cap). */
    private var speechRun = 0
    /** A segment with clear speech (≥ 1 s) gave no text: the caller should say it didn't catch that instead of waiting. */
    @Volatile var missedSpeech = false

    @Synchronized fun resetUtterance() { vad.reset(); filled = 0; heardSpeech = false; speechRun = 0; missedSpeech = false }

    /** Feed live audio; returns a finished utterance's text once the speaker pauses. */
    @Synchronized fun accept(samples: FloatArray): String? {
        var i = 0
        var result: String? = null
        while (i < samples.size) {
            val n = minOf(512 - filled, samples.size - i)
            System.arraycopy(samples, i, window, filled, n)
            filled += n; i += n
            if (filled == 512) {
                vad.acceptWaveform(window.copyOf())
                filled = 0
                if (vad.isSpeechDetected()) { heardSpeech = true; speechRun += 512 } else speechRun = 0
                // hard cap: someone who never pauses (or steady noise the VAD takes for speech) still gets an answer
                if (speechRun > SpeechChunks.MAX_UTTERANCE_S * SAMPLE_RATE) { vad.flush(); speechRun = 0 }
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    val text = transcribe(seg.samples)
                    if (text.isNotBlank()) result = ((result?.plus(" ") ?: "") + text).trim()
                    else if (seg.samples.size >= SAMPLE_RATE) missedSpeech = true
                }
            }
        }
        return result
    }

    /** End of listening window: flush whatever speech is buffered. */
    @Synchronized fun finish(): String? {
        vad.flush()
        var result: String? = null
        while (!vad.empty()) {
            val seg = vad.front(); vad.pop()
            val text = transcribe(seg.samples)
            if (text.isNotBlank()) result = ((result?.plus(" ") ?: "") + text).trim()
            else if (seg.samples.size >= SAMPLE_RATE) missedSpeech = true
        }
        return result
    }

    /** Long speech is decoded in ≤ 8 s pieces (the model returns nothing for more than ~9 s); never throws. */
    fun transcribe(samples: FloatArray): String =
        SpeechChunks.split(samples, SAMPLE_RATE).joinToString(" ") { r -> decodeOne(if (r.first == 0 && r.last == samples.size - 1) samples else samples.copyOfRange(r.first, r.last + 1)) }
            .replace(Regex("\\s+"), " ").trim()

    private fun decodeOne(samples: FloatArray): String = try {
        val s = recognizer.createStream()
        try { s.acceptWaveform(samples, SAMPLE_RATE); recognizer.decode(s); recognizer.getResult(s).text.trim() } finally { s.release() }
    } catch (e: Throwable) { android.util.Log.w("BelleStt", "decode failed on ${samples.size / SAMPLE_RATE.toFloat()} s", e); "" }

    @Synchronized fun release() { recognizer.release(); vad.release() }
}

/** Text-to-speech: Piper (VITS) voices via sherpa-onnx, played with AudioTrack. */
class TextToSpeech(ctx: Context, voiceDirName: String, quality: Quality = Quality()) {
    /** Piper/VITS sampling noise: [noise] = expressiveness (0.667 is Piper's own default), [noiseW] = rhythm variation (0.8). */
    data class Quality(val noise: Float = 0.667f, val noiseW: Float = 0.8f, val threads: Int = 2)
    val voiceDir: File = File(ModelFiles.voicesDir(ctx), voiceDirName).takeIf { it.isDirectory }
        ?: ModelFiles.installedVoices(ctx).firstOrNull()?.let { File(ModelFiles.voicesDir(ctx), it) }
        ?: throw IllegalStateException("No voice installed yet. Add one in Settings › Voice.")
    private val tts: OfflineTts
    val engine: String

    init {
        val onnx = voiceDir.listFiles()!!.filter { it.name.endsWith(".onnx") }.maxBy { it.length() }
        val dataDir = File(voiceDir, "espeak-ng-data")
        val voicesBin = File(voiceDir, "voices.bin")
        engine = if (voicesBin.isFile) "kokoro" else "piper"
        tts = OfflineTts(null, OfflineTtsConfig(
            model = if (engine == "kokoro") OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = onnx.path, voices = voicesBin.path, tokens = File(voiceDir, "tokens.txt").path,
                    dataDir = if (dataDir.isDirectory) dataDir.path else "",
                ),
                numThreads = maxOf(quality.threads, 2),
            ) else OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.path,
                    tokens = File(voiceDir, "tokens.txt").path,
                    dataDir = if (dataDir.isDirectory) dataDir.path else "",
                    noiseScale = quality.noise.coerceIn(0f, 1.5f),
                    noiseScaleW = quality.noiseW.coerceIn(0f, 1.5f),
                ),
                numThreads = quality.threads,
            ),
        ))
    }

    val numSpeakers: Int get() = tts.numSpeakers()

    /** Synthesize the whole reply (it's short) and return PCM float samples + rate. */
    fun synth(text: String, speaker: Int, speed: Float): Pair<FloatArray, Int> {
        val a = tts.generate(text, speaker.coerceIn(0, maxOf(0, numSpeakers - 1)), speed)
        return AudioOut.clean(a.samples, a.sampleRate) to a.sampleRate
    }

    /** Blocking playback. */
    fun speak(text: String, speaker: Int, speed: Float) {
        val (samples, rate) = synth(text, speaker, speed)
        if (samples.isNotEmpty()) AudioOut.play(samples, rate)
    }

    fun release() { tts.release() }
}

/** Plays PCM float audio (TTS output and chimes). Works without any model loaded. */
object AudioOut {
    @Volatile private var stopRequested = false
    /** Debug only: also save every played clip as <wall-clock ms>.wav here (for muxing audio into proof videos). */
    @Volatile var dumpDir: java.io.File? = null
    /** Assistant voice volume (Settings › Voice), read at each playback. */
    @Volatile var volumeProvider: () -> Float = { 1f }
    /** Set at startup: used to take transient, ducking audio focus while speaking (music dips, then comes back). */
    @Volatile var audioManager: android.media.AudioManager? = null
    /** Volume the last clip actually played at (the voice slider), for diagnostics. */
    @Volatile var lastVolume = -1f

    fun dumpWav(dir: java.io.File, samples: FloatArray, rate: Int, startMs: Long = System.currentTimeMillis()) = runCatching {
        dir.mkdirs(); val n = samples.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (x in samples) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        java.io.File(dir, "$startMs.wav").writeBytes(b.array())
    }

    /**
     * Played exactly at the model's own sample rate (AudioTrack resamples to the device rate in the mixer, once,
     * band-limited). Here only: a peak limiter (VITS can overshoot 1.0 and the float path would hard-clip) and 4 ms
     * fades so the clip doesn't start or end with a click.
     */
    fun clean(x: FloatArray, rate: Int): FloatArray {
        if (x.isEmpty()) return x
        var peak = 0f; for (v in x) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        val out = if (peak > 0.97f) { val k = 0.97f / peak; FloatArray(x.size) { x[it] * k } } else x.copyOf()
        val f = minOf(out.size / 2, rate * 4 / 1000)
        for (i in 0 until f) { val g = i.toFloat() / f; out[i] *= g; out[out.size - 1 - i] *= g }
        return out
    }

    fun play(samples: FloatArray, rate: Int) {
        stopRequested = false
        dumpDir?.let { d -> Thread { dumpWav(d, samples, rate) }.start() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(samples.size * 4, 4096))
            .build()
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        val vol = runCatching { volumeProvider() }.getOrDefault(1f).coerceIn(0f, 1f); lastVolume = vol
        t.setVolume(vol)
        val focus = audioManager?.let { am ->
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build().also { runCatching { am.requestAudioFocus(it) } }
        }
        try {
            app.belle.assistant.avatar.LipSync.begin(samples, rate)
            t.play()
            val end = System.currentTimeMillis() + samples.size * 1000L / rate + 150
            while (System.currentTimeMillis() < end && !stopRequested) Thread.sleep(30)
        } finally {
            runCatching { t.stop() }; t.release()
            app.belle.assistant.avatar.LipSync.end()
            focus?.let { f -> runCatching { audioManager?.abandonAudioFocusRequest(f) } }
        }
    }

    /** Bumped by every stop() so multi-part speech can tell it was interrupted. */
    @Volatile var stops = 0
    fun stop() { stopRequested = true; stops++ }
}

/** Tiny generated chimes (no audio files needed): rising = listening, falling = done listening. */
object Chime {
    private fun tones(rate: Int, amp: Double, vararg notes: Pair<Double, Double>): FloatArray {
        val total = notes.sumOf { it.second }
        val n = (rate * total).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var acc = 0.0; var f = notes.last().first
            for ((freq, dur) in notes) { if (t < acc + dur) { f = freq; break }; acc += dur }
            val env = minOf(1.0, i / 200.0) * minOf(1.0, (n - i) / 600.0)
            (amp * env * Math.sin(2 * Math.PI * f * t)).toFloat()
        }
    }
    /** Softer single blip when it keeps listening after a reply (continuous conversation). */
    fun followSamples(rate: Int = 22050) = tones(rate, 0.14, 1320.0 to 0.07)
    /** Falling three-note "goodnight" for sleep words / End. */
    fun sleepSamples(rate: Int = 22050) = tones(rate, 0.2, 988.0 to 0.1, 784.0 to 0.1, 523.0 to 0.22)

    fun stopSamples(rate: Int = 22050): FloatArray {
        val n = (rate * 0.16).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val f = if (t < 0.08) 1100.0 else 740.0
            val env = minOf(1.0, i / 200.0) * minOf(1.0, (n - i) / 400.0)
            (0.18 * env * Math.sin(2 * Math.PI * f * t)).toFloat()
        }
    }
    fun samples(rate: Int = 22050): FloatArray {
        val n = (rate * 0.18).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val f = if (t < 0.09) 880.0 else 1320.0
            val env = minOf(1.0, i / 200.0) * minOf(1.0, (n - i) / 400.0)
            (0.25 * env * Math.sin(2 * Math.PI * f * t)).toFloat()
        }
    }
}
