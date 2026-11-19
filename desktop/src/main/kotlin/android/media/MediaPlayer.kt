package android.media

import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * Desktop stand-in for android.media.MediaPlayer (short sounds: chimes, voice samples). Decodes with Java Sound plus
 * the bundled MP3 and Ogg Vorbis decoders (mp3spi, vorbisspi); WAV/AIFF natively. Plays on its own thread.
 */
class MediaPlayer {
    fun interface OnCompletionListener { fun onCompletion(mp: MediaPlayer) }
    fun interface OnErrorListener { fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean }
    private var file: File? = null; private var bytes: ByteArray? = null
    private var done: OnCompletionListener? = null; private var err: OnErrorListener? = null
    @Volatile private var vol = 1f; @Volatile private var stopped = false
    private var pcm: ByteArray? = null; private var fmt: javax.sound.sampled.AudioFormat? = null
    private var thread: Thread? = null

    fun setAudioAttributes(a: AudioAttributes) {}
    fun setDataSource(path: String) { file = File(path) }
    fun setDataSource(ctx: android.content.Context, uri: android.net.Uri) { file = File(uri.path ?: error("no file for $uri")) }
    fun setDataSource(fd: java.io.FileDescriptor, off: Long, len: Long) {
        val f = android.content.res.AssetFileDescriptor.fileOf(fd) ?: error("unknown asset")
        bytes = f.readBytes().copyOfRange(off.toInt(), (off + len).toInt())
    }
    fun setOnCompletionListener(l: OnCompletionListener) { done = l }
    fun setOnErrorListener(l: OnErrorListener) { err = l }
    fun setVolume(l: Float, r: Float) { vol = l.coerceIn(0f, 1f) }
    var isLooping = false
    fun prepare() {
        val viaJavaSound = runCatching {
            val src = bytes?.let { AudioSystem.getAudioInputStream(java.io.BufferedInputStream(it.inputStream())) } ?: AudioSystem.getAudioInputStream(file!!)
            val base = src.format
            val target = javax.sound.sampled.AudioFormat(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false)
            AudioSystem.getAudioInputStream(target, src).use { pcm = it.readAllBytes() }; fmt = target
        }
        if (viaJavaSound.isSuccess) return
        // MP4 / WebM / M4A (video background sound): decoded by FFmpeg when it is installed
        val ff = file?.let { app.belle.assistant.avatar.BackgroundDecoders.ffmpeg() } ?: throw viaJavaSound.exceptionOrNull()!!
        val p = ProcessBuilder(ff, "-v", "error", "-i", file!!.path, "-vn", "-ac", "2", "-ar", "44100", "-f", "s16le", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        pcm = p.inputStream.use { it.readAllBytes() }; p.waitFor()
        if (pcm!!.isEmpty()) throw viaJavaSound.exceptionOrNull()!!
        fmt = javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false)
    }
    fun start() {
        val data = pcm ?: return; val f = fmt ?: return
        thread = Thread({
            try {
                val l = runCatching { AudioSystem.getSourceDataLine(f).apply { open(f) } }.getOrNull()
                if (l == null) Thread.sleep((data.size / f.frameSize * 1000L / f.sampleRate.toLong()).coerceAtMost(30_000))
                else {
                    l.start(); val chunk = ByteArray(8192)
                    do {   // the volume is applied per chunk, so setVolume works while playing (background sound slider)
                        var i = 0
                        while (i < data.size && !stopped) { val k = minOf(chunk.size, data.size - i); val g = vol
                            for (j in 0 until k / 2) { val s = ((data[i + 2 * j].toInt() and 255) or (data[i + 2 * j + 1].toInt() shl 8)).toShort().toInt(); val v = (s * g).toInt(); chunk[2 * j] = v.toByte(); chunk[2 * j + 1] = (v shr 8).toByte() }
                            l.write(chunk, 0, k); i += k }
                    } while (isLooping && !stopped)
                    if (!stopped) l.drain(); l.close()
                }
                done?.onCompletion(this)
            } catch (t: Throwable) { if (err?.onError(this, 1, 0) != true) done?.onCompletion(this) }
        }, "media-player").apply { isDaemon = true; start() }
    }
    fun stop() { stopped = true }
    fun release() { stopped = true }
    val isPlaying get() = thread?.isAlive == true
}
