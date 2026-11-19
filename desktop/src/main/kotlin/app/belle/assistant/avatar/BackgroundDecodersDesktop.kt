package app.belle.assistant.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.belle.assistant.avatar.Backgrounds.Clip
import app.belle.assistant.avatar.Backgrounds.MAX_FRAMES
import app.belle.assistant.avatar.Backgrounds.MAX_PX
import app.belle.assistant.avatar.Backgrounds.VIDEO_WINDOW_MS
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop twin of the phone's background decoders. Stills, GIF, animated WebP and AVIF go through Skia's codecs
 * (the same decoders Android's ImageDecoder wraps). Videos (MP4 / WebM) go through FFmpeg when it is installed
 * (Setup › Video backgrounds, or ffmpeg on PATH); without it the import says so instead of failing silently.
 */
internal object BackgroundDecoders {
    fun small(b: Bitmap): Bitmap {
        val s = MAX_PX.toFloat() / maxOf(b.width, b.height)
        return if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
    }
    fun still(f: File): Bitmap = small(BitmapFactory.decodeFile(f.path) ?: error("not an image"))

    /** Every frame of an animated image (GIF, WebP, AVIF), thinned to ≤ [MAX_FRAMES] and ≥ 66 ms per frame (≤ 15 fps), as on the phone. */
    private fun animated(f: File): Clip {
        val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(f.readBytes()))
        if (codec.frameCount <= 1) return Clip(listOf(still(f)), intArrayOf(1000))
        val info = codec.framesInfo
        val keep = maxOf(1, (codec.frameCount + MAX_FRAMES - 1) / MAX_FRAMES)
        val frames = ArrayList<Bitmap>(); val delays = ArrayList<Int>()
        val sb = org.jetbrains.skia.Bitmap(); sb.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(codec.width, codec.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
        var acc = 0
        for (i in 0 until codec.frameCount) {
            codec.readPixels(sb, i)   // Skia composes each frame onto the ones it depends on
            acc += info[i].duration.coerceAtLeast(20)
            if (i % keep == keep - 1 || i == codec.frameCount - 1) { frames += small(Bitmap.fromSkia(sb)); delays += maxOf(66, acc); acc = 0 }
        }
        return Clip(frames, delays.toIntArray())
    }
    fun gif(f: File): Clip = animated(f)
    fun avif(f: File): Clip = runCatching { animated(f) }.getOrElse { error("this AVIF can't be read on this PC") }

    /** ffmpeg from Setup (runtimes/ffmpeg) or on PATH. */
    fun ffmpeg(): String? {
        val exe = if (app.belle.desktop.Paths.isWindows) "ffmpeg.exe" else "ffmpeg"
        File(app.belle.desktop.Paths.runtimes, "ffmpeg/$exe").takeIf { it.canExecute() }?.let { return it.path }
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.canExecute() }?.path
    }

    /** One FFmpeg pass: the first [VIDEO_WINDOW_MS] at 8 fps, scaled to fit [MAX_PX], raw RGBA frames on stdout. */
    fun video(f: File, firstFrame: ((Clip) -> Unit)? = null): Clip {
        val ff = ffmpeg() ?: error("video backgrounds need FFmpeg (Settings › Downloads › Video backgrounds). GIF, WebP and AVIF work without it")
        val size = probeSize(ff, f) ?: error("not a video FFmpeg can read")
        val s = minOf(1f, MAX_PX.toFloat() / maxOf(size.first, size.second))
        val w = ((size.first * s).toInt() / 2 * 2).coerceAtLeast(2); val h = ((size.second * s).toInt() / 2 * 2).coerceAtLeast(2)
        val p = ProcessBuilder(ff, "-v", "error", "-t", (VIDEO_WINDOW_MS / 1000).toString(), "-i", f.path, "-an", "-vf", "fps=8,scale=$w:$h",
            "-frames:v", MAX_FRAMES.toString(), "-pix_fmt", "rgba", "-f", "rawvideo", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val frames = ArrayList<Bitmap>(); val buf = ByteArray(w * h * 4)
        p.inputStream.use { inp ->
            while (frames.size < MAX_FRAMES) {
                var n = 0; while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                if (n < buf.size) break
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(buf)); frames += b
                if (frames.size == 1) firstFrame?.invoke(Clip(listOf(b), intArrayOf(1000)))
            }
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        if (frames.isEmpty()) error("no frames (FFmpeg couldn't decode this video)")
        return Clip(frames, IntArray(frames.size) { 125 })
    }

    private fun probe(ff: String, f: File): String = runCatching {
        val p = ProcessBuilder(ff, "-hide_banner", "-i", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(10, TimeUnit.SECONDS); out
    }.getOrDefault("")
    private fun probeSize(ff: String, f: File): Pair<Int, Int>? =
        Regex("Video:.*?(\\d{2,5})x(\\d{2,5})").find(probe(ff, f))?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    fun hasAudio(f: File): Boolean = ffmpeg()?.let { probe(it, f).contains("Audio:") } ?: false
}
