package android.media

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Desktop stand-in for android.media.AudioTrack over Java Sound (WASAPI/DirectSound on Windows), 16-bit mono at the
 * clip's own rate. MODE_STATIC buffers the clip and plays it on [play]; MODE_STREAM writes block like Android's.
 * With no audio device (CI, a PC with no speakers) it becomes a timed null sink so callers still pace correctly.
 */
class AudioTrack private constructor(private val rate: Int, private val mode: Int, bufferBytes: Int) {
    private val line: SourceDataLine? = runCatching {
        val f = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false)
        AudioSystem.getSourceDataLine(f).apply { open(f, maxOf(4096, bufferBytes / 2 / 2 * 2)) }
    }.getOrNull()
    private val staticBuf = java.io.ByteArrayOutputStream()
    @Volatile private var gain = 1f
    @Volatile var playState = PLAYSTATE_STOPPED; private set
    val state get() = STATE_INITIALIZED
    val sampleRate get() = rate
    private var player: Thread? = null
    private var nullFrames = 0L; private var nullStart = 0L

    class Builder {
        private var fmt: AudioFormat? = null; private var mode = MODE_STREAM; private var bytes = 0
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setAudioFormat(f: AudioFormat) = apply { fmt = f }
        fun setTransferMode(m: Int) = apply { mode = m }
        fun setBufferSizeInBytes(b: Int) = apply { bytes = b }
        fun setPerformanceMode(m: Int) = this
        fun build() = AudioTrack(fmt?.sampleRate ?: 22050, mode, bytes)
    }

    private fun pcm(x: FloatArray, off: Int, n: Int): ByteArray {
        val b = ByteArray(n * 2); val g = gain
        for (i in 0 until n) { val v = (x[off + i] * g).coerceIn(-1f, 1f); val s = (v * 32767f).toInt()
            b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
        return b
    }

    fun write(x: FloatArray, off: Int, n: Int, how: Int): Int {
        if (mode == MODE_STATIC) { synchronized(staticBuf) { staticBuf.write(pcm(x, off, n)) }; return n }
        val l = line
        if (l == null) {   // null sink: sleep for the clip's duration so streaming code keeps real time
            if (nullStart == 0L) nullStart = System.nanoTime()
            nullFrames += n; val ahead = nullFrames * 1_000_000_000L / rate - (System.nanoTime() - nullStart)
            if (ahead > 250_000_000L) Thread.sleep((ahead - 250_000_000L) / 1_000_000)
            return n
        }
        val b = pcm(x, off, n); l.write(b, 0, b.size); return n
    }

    fun play() {
        playState = PLAYSTATE_PLAYING
        if (mode == MODE_STATIC) {
            val data = synchronized(staticBuf) { staticBuf.toByteArray() }
            val l = line ?: return
            player = Thread({ l.start(); var i = 0; while (i < data.size && playState == PLAYSTATE_PLAYING) { val k = minOf(4096, data.size - i); l.write(data, i, k); i += k }; l.drain() }, "audio-static").apply { isDaemon = true; start() }
        } else line?.start()
    }
    fun pause() { playState = PLAYSTATE_PAUSED; line?.stop() }
    fun stop() { playState = PLAYSTATE_STOPPED; line?.stop(); line?.flush(); nullStart = 0; nullFrames = 0 }
    fun flush() { line?.flush() }
    fun release() { stop(); runCatching { line?.close() } }
    fun setVolume(v: Float): Int { gain = v.coerceIn(0f, 1f); return 0 }
    val playbackHeadPosition: Int get() = line?.framePosition?.toInt() ?: 0
    val audioSessionId get() = 0

    companion object {
        const val MODE_STATIC = 0; const val MODE_STREAM = 1; const val WRITE_BLOCKING = 0; const val WRITE_NON_BLOCKING = 1
        const val PLAYSTATE_STOPPED = 1; const val PLAYSTATE_PAUSED = 2; const val PLAYSTATE_PLAYING = 3
        const val STATE_INITIALIZED = 1; const val STATE_UNINITIALIZED = 0
        @JvmStatic fun getMinBufferSize(rate: Int, ch: Int, enc: Int) = rate / 10 * 4
    }
}
