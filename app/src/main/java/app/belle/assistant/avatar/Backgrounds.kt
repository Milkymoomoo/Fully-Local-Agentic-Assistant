package app.belle.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
            ext in VIDEO -> BackgroundDecoders.video(f, firstFrame)
            ext == "gif" -> BackgroundDecoders.gif(f)
            ext == "avif" -> BackgroundDecoders.avif(f)
            else -> Clip(listOf(BackgroundDecoders.still(f)), intArrayOf(1000))
        }
    }

    /** Longest stretch of a video turned into background frames (it loops); sampled at ≤ 8 fps. */
    const val VIDEO_WINDOW_MS = 12_000L

    /** True if the file has an audio track (the sound switch says "no audio in this file" otherwise). */
    fun hasAudio(f: File): Boolean = BackgroundDecoders.hasAudio(f)

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
