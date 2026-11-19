package app.belle.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.belle.assistant.Prefs
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.avatar.Backgrounds
import app.belle.assistant.service.AssistantRuntime
import java.io.File
import java.util.concurrent.Executors

/**
 * The widget's always-on idle animation (breathing, blinking, sway; 2D puppet or 3D), as a short pre-rendered loop
 * that the launcher plays by itself in a ViewFlipper (autoStart). So it keeps moving while our process sleeps, costs
 * nothing when the screen is off or the widget isn't visible (ViewFlipper stops itself then), and only the talking
 * animation still uses live updates.
 *
 * Frames are rendered off the main thread at a fixed clock, the end crossfaded into the start so the loop has no
 * seam, cached on disk per profile + look (picture, mesh, shader, framing, background, detail), and sized/counted to
 * stay well inside the RemoteViews bitmap-memory limit.
 */
object WidgetIdleLoop {
    const val VERSION = 3   // 3: matting-model cutout
    const val FPS = 8                 // smooth enough for breathing/blinks; ViewFlipper flips are cheap at this rate
    const val SECONDS = 3             // loop length
    private const val BLEND = 6       // frames crossfaded across the seam

    data class Loop(val frames: List<Bitmap>, val intervalMs: Int, val profileId: String = "", val mode: String = "")

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-idle").apply { priority = Thread.MIN_PRIORITY } }
    private val pending = HashSet<String>()
    private val mem = HashMap<String, Loop>()

    /** Bytes we allow for one widget's loop: half the platform's RemoteViews limit (1.5 × screen × 4 B), at most 8 MB. */
    fun budgetBytes(ctx: Context): Long {
        val dm = ctx.resources.displayMetrics
        val limit = (1.5 * dm.widthPixels * dm.heightPixels * 4).toLong()
        return minOf(limit / 2, 8L shl 20)
    }

    /** Frame count and pixel size (keeps aspect, never above the requested size) that fit the budget. */
    fun plan(ctx: Context, w: Int, h: Int): Triple<Int, Int, Int> {
        val n = FPS * SECONDS
        val perFrame = budgetBytes(ctx) / n / 4
        val k = minOf(1.0, Math.sqrt(perFrame.toDouble() / (w.toLong() * h)))
        return Triple(n, (w * k).toInt().coerceAtLeast(32), (h * k).toInt().coerceAtLeast(32))
    }

    private fun mtime(f: File?) = f?.takeIf { it.exists() }?.lastModified() ?: 0L
    fun key(ctx: Context, p: Prefs, mode: String, w: Int, h: Int): String {
        val avatar = p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) }
        return listOf(VERSION, p.id, mode, w, h, p.avatarFile, mtime(avatar), mtime(Avatar3DFiles.mesh(ctx, p.id)), p.framing("widget").toLine(),
            p.shader3d.id, p.shader3dIntensity, p.psxNoDepth, p.n64Fog, p.animStrength, p.detailFor("widget"), p.avatarAnimate, p.avatarMouth, p.avatarGifMode,
            p.bgMode, p.bgFile, mtime(Backgrounds.file(ctx, p)), FramingRev.rev).joinToString("|").hashCode().toUInt().toString(16)
    }

    private fun dir(ctx: Context, key: String) = File(File(ctx.filesDir, "widget_idle"), key)

    /** Cached loop, or null while it's being made ([onReady] runs on the render thread when it is). */
    /** The loop shown last: kept on screen while a new look of the SAME assistant and mode renders (never another assistant's). */
    @Volatile private var last: Loop? = null
    fun lastFor(plan: app.belle.assistant.core.WidgetPlan.Plan): Loop? = last?.takeIf { app.belle.assistant.core.WidgetPlan.canReuse(it.profileId, it.mode, plan) }
    fun get(ctx: Context, p: Prefs, mode: String, w: Int, h: Int, onReady: () -> Unit): Loop? =
        getNew(ctx, p, mode, w, h, onReady)?.also { last = it }

    private fun getNew(ctx: Context, p: Prefs, mode: String, w: Int, h: Int, onReady: () -> Unit): Loop? {
        val (n, fw, fh) = plan(ctx, w, h)
        val key = key(ctx, p, mode, fw, fh)
        synchronized(mem) { mem[key]?.let { return it } }
        val d = dir(ctx, key)
        val files = (0 until n).map { File(d, "f%02d.png".format(it)) }
        if (files.all { it.length() > 0 }) {
            val fr = files.mapNotNull { BitmapFactory.decodeFile(it.path) }
            if (fr.size == n) return Loop(fr, 1000 / FPS, p.id, mode).also { l -> synchronized(mem) { mem.clear(); mem[key] = l } }
        }
        synchronized(pending) { if (!pending.add(key)) return null }
        val app = ctx.applicationContext; val pid = p.id
        pool.execute {
            try {
                val t0 = System.currentTimeMillis()
                val fr = render(app, Prefs(app, pid), mode, fw, fh, n)
                d.mkdirs(); fr.forEachIndexed { i, b -> File(d, "f%02d.png".format(i)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                // keep only the newest few loops on disk
                d.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.deleteRecursively() }
                synchronized(mem) { mem.clear(); mem[key] = Loop(fr, 1000 / FPS, pid, mode) }
                AssistantRuntime.event("widget idle loop: $n frames ${fw}x$fh ($mode) in ${System.currentTimeMillis() - t0} ms, ${n * fw * fh * 4 / 1024} KB")
                onReady()
            } catch (e: Throwable) { AssistantRuntime.event("widget idle loop failed: $e") }
            finally { synchronized(pending) { pending.remove(key) } }
        }
        return null
    }

    /** Renders n+BLEND frames at a fixed clock, then crossfades the extra tail into the head so frame n-1 → 0 is seamless. */
    /** Renders [p]'s avatar (the profile the cache key was made for, never "whoever is answering"). */
    private fun render(ctx: Context, p: Prefs, mode: String, w: Int, h: Int, n: Int): List<Bitmap> {
        val v = app.belle.assistant.avatar.AvatarView(ctx, "widget", fill = true, profileId = p.id).apply {
            force3d = mode == "3d"; syncRender = true; waitForBackground = true
            setImageFile(p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) })
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); layout(0, 0, w, h)
            state = AssistantRuntime.Ui.IDLE
        }
        val step = 1000L / FPS
        val warm = if (mode == "3d") 0L else 1500L   // 2D: let the idle motion settle into its rhythm first (3D is a pure function of time)
        var t = 0L
        while (t < warm) { v.clockMs = t; v.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))); t += step }
        // Render a little extra and start the loop where its end looks most like its start, so the crossfade below
        // only has to hide a small difference (no "double head" mid-blink or mid-sway).
        val extra = SEARCH
        val raw = (0 until n + BLEND + extra).map { i ->
            v.clockMs = warm + i * step
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        val thumbs = raw.map { lumaThumb(it) }
        val start = (0..extra).minByOrNull { st -> (0 until BLEND).sumOf { j -> dist(thumbs[st + j], thumbs[st + n + j]) } } ?: 0
        val out = raw.subList(start, start + n).toMutableList()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (j in 0 until BLEND) {
            // frame j continues on from frame n-1 (= raw[start+n+j]) and drifts into the real raw[start+j]
            val a = (j + 1f) / (BLEND + 1f)
            val b = raw[start + n + j].copy(Bitmap.Config.ARGB_8888, true)
            paint.alpha = (255 * a).toInt(); Canvas(b).drawBitmap(raw[start + j], 0f, 0f, paint)
            out[j] = b
        }
        raw.forEachIndexed { i, b -> if (i !in start until start + n) b.recycle() }
        return out
    }

    private const val SEARCH = 16
    private fun lumaThumb(b: Bitmap): IntArray {
        val t = Bitmap.createScaledBitmap(b, 24, 24, true); val px = IntArray(576); t.getPixels(px, 0, 24, 0, 0, 24, 24); t.recycle()
        return IntArray(576) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 * (c ushr 24) / 255 }
    }
    private fun dist(a: IntArray, b: IntArray): Long { var s = 0L; for (i in a.indices) s += Math.abs(a[i] - b[i]); return s }

    fun forget() { synchronized(mem) { mem.clear() }; last = null }
}
