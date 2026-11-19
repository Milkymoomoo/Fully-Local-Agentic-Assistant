package app.belle.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import app.belle.assistant.Prefs
import java.io.File
import java.util.concurrent.Executors

/**
 * Per-profile looping backgrounds behind the avatar (Home circle, bubble, widget).
 * Formats: JPG, PNG, BMP, WebP, GIF (animated), AVIF (still; animated on Android 12+), MP4 and WebM (VP8/VP9/AV1).
 * Everything is decoded ONCE into a short list of small frames (≤ [MAX_PX], RGB_565) so every surface — including the
 * widget, which can't play video — just picks the frame for the current time at its own (low) frame rate.
 * Videos are sampled at ≤ 8 fps, at most [MAX_FRAMES] frames, spread over at most 60 s.
 */
object Backgrounds {
    const val DIR = "backgrounds"
    const val MAX_PX = 320
    const val MAX_FRAMES = 96
    val STILL = setOf("png", "jpg", "jpeg", "bmp", "webp")
    val ANIM = setOf("gif", "avif")
    val VIDEO = setOf("mp4", "webm", "m4v", "mkv")
    val EXTENSIONS = STILL + ANIM + VIDEO
    val MIME_TYPES = arrayOf("image/png", "image/jpeg", "image/bmp", "image/x-ms-bmp", "image/webp", "image/gif", "image/avif", "video/mp4", "video/webm")

    class Clip(val frames: List<Bitmap>, val delaysMs: IntArray) {
        val totalMs = delaysMs.sum().coerceAtLeast(1)
        val animated get() = frames.size > 1
        fun at(tMs: Long): Bitmap { if (frames.size == 1) return frames[0]
            var t = (tMs % totalMs).toInt(); for (i in frames.indices) { t -= delaysMs[i]; if (t < 0) return frames[i] }; return frames.last() }
    }

    private val cache = HashMap<String, Clip>()
    private val jobs = HashMap<String, java.util.concurrent.Future<Clip?>>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "belle-bg").apply { priority = Thread.MIN_PRIORITY } }
    /** Files that failed to decode (or killed the process while decoding) — never tried again. */
    private val bad = HashSet<String>()
    @Volatile var lastError: String? = null
    private var appCtx: Context? = null

    fun file(ctx: Context, p: Prefs): File? = if (p.bgMode != "custom" || p.bgFile.isEmpty()) null
        else File(File(ctx.filesDir, DIR), p.bgFile).takeIf { it.exists() && !isBad(ctx, it.name) }
    fun isVideo(f: File) = f.extension.lowercase() in VIDEO
    private fun badFile(ctx: Context) = File(ctx.filesDir, "$DIR/.bad")
    fun isBad(ctx: Context, name: String): Boolean = synchronized(bad) {
        if (bad.isEmpty()) runCatching { badFile(ctx).takeIf { it.exists() }?.readLines()?.let { bad += it } }
        name in bad
    }
    private fun markBad(ctx: Context, name: String, why: String) {
        synchronized(bad) { if (bad.add(name)) runCatching { badFile(ctx).apply { parentFile?.mkdirs() }.appendText(name + "\n") } }
        lastError = why
        app.belle.assistant.service.AssistantRuntime.event("background: $name can't be shown ($why); using the picture's own background")
    }

    /**
     * Safe mode: a marker is written while a file is being decoded. If it is still there when the app starts, the
     * process died inside the decoder (native crash / out of memory) — that file is put on the bad list and every
     * profile using it goes back to the picture's own background, so a bad file can never black-screen the app twice.
     */
    @Volatile private var recovered = false
    fun recoverAfterCrash(ctx: Context): String? {
        if (recovered) return null; recovered = true
        val m = File(ctx.filesDir, "$DIR/.decoding"); if (!m.exists()) return null
        val name = runCatching { m.readText().trim() }.getOrDefault(""); m.delete()
        if (name.isEmpty()) return null
        // one interrupted decode can be an ordinary kill (swiped away, low memory); twice in a row = this file kills us
        val strikes = File(ctx.filesDir, "$DIR/.strikes")
        val prev = runCatching { strikes.readText().split('|') }.getOrNull()
        val n = if (prev?.getOrNull(0) == name) (prev.getOrNull(1)?.toIntOrNull() ?: 0) + 1 else 1
        if (n < 2) { runCatching { strikes.writeText("$name|$n") }; return null }
        strikes.delete()
        markBad(ctx, name, "the app stopped while reading it")
        app.belle.assistant.ProfileStore.ids(ctx).forEach { id -> val p = app.belle.assistant.ProfileStore.prefsOf(ctx, id); if (p.bgFile == name) p.bgMode = "own" }
        return name
    }

    /** Decode with the crash marker around it and a time limit; null (and the file marked bad) on failure. */
    private fun guardedLoad(ctx: Context, f: File): Clip? {
        val m = File(ctx.filesDir, "$DIR/.decoding")
        return try {
            runCatching { m.parentFile?.mkdirs(); m.writeText(f.name) }
            load(f) { first ->
                // progressive: show the first decoded frame right away while the rest of the loop decodes
                val key = f.path + "|" + f.lastModified()
                synchronized(cache) { if (cache[key] == null) cache[key] = first }
                onDecoded.forEach { runCatching { it() } }
            }.also { File(ctx.filesDir, "$DIR/.strikes").delete() }
        } catch (e: Throwable) {
            android.util.Log.w("Backgrounds", "can't decode ${f.name}", e); markBad(ctx, f.name, e.message ?: e.javaClass.simpleName); null
        } finally { m.delete() }
    }

    private fun job(ctx: Context, f: File): java.util.concurrent.Future<Clip?> {
        val key = f.path + "|" + f.lastModified()
        appCtx = ctx.applicationContext
        synchronized(cache) {
            jobs[key]?.let { return it }
            val app = ctx.applicationContext
            val fut = java.util.concurrent.FutureTask<Clip?> {
                val clip = guardedLoad(app, f)
                synchronized(cache) { if (cache.size > 3) cache.clear(); if (clip != null) cache[key] = clip; jobs.remove(key) }
                if (clip != null) onDecoded.forEach { runCatching { it() } }
                clip
            }
            jobs[key] = fut; exec.execute(fut); return fut
        }
    }
    /** Called (on the decode thread) whenever a background finished decoding, so surfaces can redraw. */
    val onDecoded = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** The frame to show now, or null (no custom background, or still decoding — never blocks; decoding is queued). */
    fun frame(ctx: Context, p: Prefs, tMs: Long): Bitmap? {
        val f = file(ctx, p) ?: return null
        val key = f.path + "|" + f.lastModified()
        synchronized(cache) { cache[key]?.let { return it.at(tMs) } }
        job(ctx, f); return null
    }
    /** Same as [frame] but waits (up to [maxWaitMs]) for the shared decode — background threads only. */
    fun frameBlocking(ctx: Context, p: Prefs, tMs: Long, maxWaitMs: Long = 20_000): Bitmap? {
        val f = file(ctx, p) ?: return null; val key = f.path + "|" + f.lastModified()
        synchronized(cache) { cache[key]?.let { return it.at(tMs) } }
        return runCatching { job(ctx, f).get(maxWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()?.at(tMs)
    }
    fun animated(ctx: Context, p: Prefs): Boolean = file(ctx, p)?.let { f -> synchronized(cache) { cache[f.path + "|" + f.lastModified()]?.animated } ?: (isVideo(f) || f.extension.lowercase() in ANIM) } ?: false

    /** Import check (off the main thread): decodes the new file with the crash guard; error text or null when OK. */
    fun validate(ctx: Context, f: File): String? =
        if (runCatching { job(ctx, f).get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() != null) null else (lastError ?: "can't be read")

    /** Decode a file into a clip (blocking). Public for tests and the import preview. */
    fun load(f: File, firstFrame: ((Clip) -> Unit)? = null): Clip {
        val ext = f.extension.lowercase()
        return when {
            ext in VIDEO -> video(f, firstFrame)
            ext == "gif" -> gif(f)
            ext == "avif" -> avif(f)
            else -> Clip(listOf(still(f)), intArrayOf(1000))
        }
    }

    private fun small(b: Bitmap): Bitmap {
        val s = MAX_PX.toFloat() / maxOf(b.width, b.height)
        val sc = if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        return sc.copy(Bitmap.Config.RGB_565, false)
    }

    private fun still(f: File): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var ss = 1; while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= MAX_PX) ss *= 2
        return small(BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss }) ?: error("not an image"))
    }

    @Suppress("DEPRECATION")
    private fun gif(f: File): Clip {
        val m = android.graphics.Movie.decodeFile(f.path) ?: return Clip(listOf(still(f)), intArrayOf(1000))
        val dur = m.duration().takeIf { it > 0 } ?: return Clip(listOf(still(f)), intArrayOf(1000))
        val step = maxOf(66, dur / MAX_FRAMES)          // ≤ 15 fps
        val s = minOf(1f, MAX_PX.toFloat() / maxOf(m.width(), m.height()))
        val frames = ArrayList<Bitmap>(); var t = 0
        while (t < dur && frames.size < MAX_FRAMES) {
            val b = Bitmap.createBitmap((m.width() * s).toInt().coerceAtLeast(1), (m.height() * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(s, s); m.setTime(t); m.draw(c, 0f, 0f)
            frames += b.copy(Bitmap.Config.RGB_565, false); t += step
        }
        return Clip(frames, IntArray(frames.size) { step })
    }

    private fun avif(f: File): Clip {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: platform AVIF (still + animated, alpha). Animated: step the drawable's frames by time.
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = MAX_PX.toFloat() / maxOf(info.size.width, info.size.height); if (s < 1) dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
            if (d is android.graphics.drawable.AnimatedImageDrawable) return animatedDrawable(d)
            val b = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, b.width, b.height); d.draw(Canvas(b)); return Clip(listOf(small(b)), intArrayOf(1000))
        }
        // Android 10/11: animated AVIF ("avis" image sequence) through the bundled AV1 decoder; stills through our own
        // HEIF container parser + the bundled decoder (or the phone's own AV1 codec)
        val bytes = f.readBytes()
        val seq = String(bytes, 8, minOf(bytes.size - 8, 32), Charsets.ISO_8859_1).contains("avis")
        if (seq) (Av1.decodeAvifSequence(bytes, MAX_PX, MAX_FRAMES) ?: Av1.decodeTrack(f, MAX_PX, MAX_FRAMES))?.let { (fr, step) -> return Clip(fr.map { small(it) }, IntArray(fr.size) { step }) }
        val b = Avif.decode(bytes, MAX_PX * 2) ?: error("this AVIF can't be read (grid / tiled images aren't supported on Android 10–11)")
        return Clip(listOf(small(b)), intArrayOf(1000))
    }

    /** Sample an AnimatedImageDrawable by letting it play in real time (no seek API) — at most ~4 s, 12 fps. */
    private fun animatedDrawable(d: android.graphics.drawable.AnimatedImageDrawable): Clip {
        val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1); d.setBounds(0, 0, w, h)
        d.repeatCount = 0; d.start()
        val frames = ArrayList<Bitmap>(); val step = 83; val t0 = System.currentTimeMillis()
        while (d.isRunning && frames.size < 48) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); d.draw(Canvas(b)); frames += small(b)
            Thread.sleep((t0 + frames.size * step - System.currentTimeMillis()).coerceAtLeast(1))
        }
        d.stop()
        return Clip(frames.ifEmpty { listOf(Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)) }, IntArray(frames.size.coerceAtLeast(1)) { step })
    }

    /** Longest stretch of a video turned into background frames (it loops); sampled at ≤ 8 fps. */
    const val VIDEO_WINDOW_MS = 12_000L

    /**
     * Video → frames in ONE sequential decode pass (MediaExtractor + MediaCodec, YUV → small RGB by direct sampling,
     * honouring the decoder's crop rectangle and stride, so odd sizes like 570×596 come out right).
     *
     * Root cause of the old black screens: frames were pulled with MediaMetadataRetriever.getScaledFrameAtTime(
     * OPTION_CLOSEST) at 96 positions. Every call seeks back to the previous key frame and decodes forward; real
     * WebMs often have a key frame only every 10 s (240 frames), so one background meant ~10 000 full-size software
     * decodes. That pinned the CPU for minutes (starving the UI and the 3D renderer until Android killed the app for
     * not starting its service in time) and still produced only 1–2 frames. One pass decodes each frame once.
     */
    private fun video(f: File, firstFrame: ((Clip) -> Unit)? = null): Clip {
        runCatching { videoSequential(f, firstFrame) }.onFailure { android.util.Log.w("Backgrounds", "sequential decode failed for ${f.name}", it) }
            .getOrNull()?.let { if (it.frames.isNotEmpty()) return it }
        // fallback: key frames only (fast seeks), then the bundled AV1 decoder
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).coerceIn(1, 60_000)
            val n = minOf(24L, maxOf(1L, dur / 500)).toInt(); val step = (dur / n).toInt().coerceAtLeast(1)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: MAX_PX
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: MAX_PX
            val s = minOf(1f, MAX_PX.toFloat() / maxOf(w, h))
            val frames = ArrayList<Bitmap>(); val t0 = System.currentTimeMillis(); var last = -1L
            for (i in 0 until n) {
                if (frames.isNotEmpty() && System.currentTimeMillis() - t0 > 10_000) break
                val b = r.getScaledFrameAtTime(i * step * 1000L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1)) ?: continue
                frames += b.copy(Bitmap.Config.RGB_565, false)
            }
            if (frames.isEmpty()) {
                Av1.decodeTrack(f, MAX_PX, MAX_FRAMES)?.let { (fr, st) -> return Clip(fr.map { small(it) }, IntArray(fr.size) { st }) }
                error("no frames (this phone can't decode the video's codec)")
            }
            return Clip(frames, IntArray(frames.size) { step })
        } finally { r.release() }
    }

    /** True if the file has an audio track (the sound switch says "no audio in this file" otherwise). */
    fun hasAudio(f: File): Boolean = runCatching {
        val ex = android.media.MediaExtractor(); try { ex.setDataSource(f.path)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true } } finally { ex.release() }
    }.getOrDefault(false)

    /** Hardware decoders first (Android 10+ can tell), then the software ones; null = let the platform pick. */
    private fun decoderFor(fmt: android.media.MediaFormat): android.media.MediaCodec? {
        val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .filter { runCatching { it.getCapabilitiesForType(mime).isFormatSupported(android.media.MediaFormat.createVideoFormat(mime,
                fmt.getInteger(android.media.MediaFormat.KEY_WIDTH), fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT))) }.getOrDefault(true) }
        val sorted = if (Build.VERSION.SDK_INT >= 29) list.sortedBy { if (it.isHardwareAccelerated && !it.isSoftwareOnly) 0 else 1 } else list
        for (ci in sorted) runCatching { return android.media.MediaCodec.createByCodecName(ci.name) }
        return null
    }
    @Volatile var lastDecoder = ""

    private fun videoSequential(f: File, firstFrame: ((Clip) -> Unit)? = null): Clip {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("no video track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track); val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)!!
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = decoderFor(fmt) ?: android.media.MediaCodec.createDecoderByType(mime); codec = c
            lastDecoder = c.name
            android.util.Log.i("Backgrounds", "${f.name}: decoding with ${c.name}")
            c.configure(fmt, null, null, 0); c.start()
            // big videos cost more per frame: shorter loop for them (12 s up to ~720p, down to 4 s for 4K)
            val px = runCatching { fmt.getInteger(android.media.MediaFormat.KEY_WIDTH).toLong() * fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT) }.getOrDefault(921_600L)
            val windowUs = (VIDEO_WINDOW_MS * 1000 * 921_600L / px.coerceAtLeast(1)).coerceIn(4_000_000L, VIDEO_WINDOW_MS * 1000)
            val stepUs = 125_000L     // ≤ 8 fps
            val frames = ArrayList<Bitmap>(); val times = ArrayList<Long>()
            var nextUs = 0L; var inEos = false; val info = android.media.MediaCodec.BufferInfo(); val t0 = System.currentTimeMillis()
            var firstPts = -1L
            loop@ while (frames.size < MAX_FRAMES) {
                if (System.currentTimeMillis() - t0 > 25_000) { if (frames.isNotEmpty()) break else error("decoding took too long") }
                if (!inEos) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0 || (firstPts >= 0 && ex.sampleTime - firstPts > windowUs + stepUs)) {
                            c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o < 0) continue
                val eos = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    if (firstPts < 0) firstPts = info.presentationTimeUs
                    val pts = info.presentationTimeUs - firstPts
                    if (pts > windowUs) { c.releaseOutputBuffer(o, false); break@loop }
                    if (pts >= nextUs) {
                        c.getOutputImage(o)?.use { img -> frames += yuvToSmall(img); times += pts }
                        if (frames.size == 1) { android.util.Log.i("Backgrounds", "${f.name}: first frame after ${System.currentTimeMillis() - t0} ms"); firstFrame?.invoke(Clip(listOf(frames[0]), intArrayOf(1000))) }
                        nextUs = pts + stepUs
                    }
                }
                c.releaseOutputBuffer(o, false)
                if (eos) break
            }
            if (frames.isEmpty()) error("no frames decoded")
            android.util.Log.i("Backgrounds", "${f.name}: ${frames.size} frames in ${System.currentTimeMillis() - t0} ms with ${c.name}")
            val delays = IntArray(frames.size) { i -> (((if (i + 1 < times.size) times[i + 1] else times[i] + stepUs) - times[i]) / 1000).toInt().coerceIn(20, 1000) }
            return Clip(frames, delays)
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release() }
    }

    /** YUV_420_888 image → RGB_565 bitmap ≤ MAX_PX, nearest-neighbour sampled straight from the planes (crop-aware). */
    private fun yuvToSmall(img: android.media.Image): Bitmap {
        val crop = img.cropRect ?: android.graphics.Rect(0, 0, img.width, img.height)
        val cw = crop.width().coerceAtLeast(1); val ch = crop.height().coerceAtLeast(1)
        val s = minOf(1f, MAX_PX.toFloat() / maxOf(cw, ch))
        val ow = (cw * s).toInt().coerceAtLeast(1); val oh = (ch * s).toInt().coerceAtLeast(1)
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        val by = py.buffer; val bu = pu.buffer; val bv = pv.buffer
        val px = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = crop.top + (y * ch / oh)
            for (x in 0 until ow) {
                val sx = crop.left + (x * cw / ow)
                val yy = (by.get(sy * py.rowStride + sx * py.pixelStride).toInt() and 0xFF) - 16
                val ci = (sy / 2) * pu.rowStride + (sx / 2) * pu.pixelStride
                val cj = (sy / 2) * pv.rowStride + (sx / 2) * pv.pixelStride
                val u = (bu.get(ci).toInt() and 0xFF) - 128; val v = (bv.get(cj).toInt() and 0xFF) - 128
                val yc = 1.164f * yy
                val r = (yc + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (yc - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (yc + 2.017f * u).toInt().coerceIn(0, 255)
                px[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.RGB_565, false)
    }

    /** Draw [b] covering [dst] (centre-crop). */
    fun drawCover(c: Canvas, b: Bitmap, dst: RectF, paint: Paint) {
        val s = maxOf(dst.width() / b.width, dst.height() / b.height)
        val w = b.width * s; val h = b.height * s
        c.drawBitmap(b, null, RectF(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2), paint)
    }

    // ---- background sound (videos only; muted unless the profile turned it on) ----
    private var player: android.media.MediaPlayer? = null
    private var playing: String? = null
    val soundPlaying: String? get() = playing
    val soundVolume: Float? get() = if (player != null) lastVol else null
    private var lastVol = 0f
    private var users = 0
    /** Called when a visible avatar surface (Home circle or bubble) appears / goes away. */
    @Synchronized fun soundUser(ctx: Context, add: Boolean) { users = (users + if (add) 1 else -1).coerceAtLeast(0); updateSound(ctx) }
    @Synchronized fun updateSound(ctx: Context) {
        val p = Prefs(ctx); val f = file(ctx, p)
        val want = if (users > 0 && f != null && isVideo(f) && p.bgSound && p.bgVolume > 0f && hasAudio(f)) f.path else null
        if (want != playing) {
            player?.release(); player = null; playing = null
            if (want != null) runCatching {
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(want); isLooping = true; prepare(); start() }
                playing = want
            }
        }
        player?.setVolume(p.bgVolume, p.bgVolume); lastVol = p.bgVolume
    }
}
