package app.belle.assistant.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import app.belle.assistant.R
import app.belle.assistant.service.AssistantRuntime.Ui
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the user's avatar (PNG / animated GIF / WebP, full alpha kept) with a
 * state ring around it:
 *   listening = bright pulsing glow, thinking = spinning arc, speaking = bouncing glow.
 * The image itself is never cropped, so transparent avatars stay transparent.
 */
/**
 * [profileId] pins the view to one assistant (the widget passes the active one). Null = follow whoever is answering
 * ([app.belle.assistant.ProfileStore.currentId]); then the 3D model is re-chosen as soon as that changes.
 */
class AvatarView(ctx: Context, var surface: String = "bubble", private val fill: Boolean = false, private val profileId: String? = null) : View(ctx) {
    // ---- 3D avatar (rendered off-screen by Avatar3DRenderer, drawn here as a bitmap) ----
    /** Widget: forced on/off by the widget's own 2D/3D chip; elsewhere follows the profile's "Show 3D" setting. */
    var force3d: Boolean? = null
    /** Render synchronously (off-screen use: the widget animator draws this view into a bitmap). */
    var syncRender = false
    private var mesh3d: File? = null
    @Volatile private var frame3d: android.graphics.Bitmap? = null
    @Volatile private var frameSmooth = true
    @Volatile private var inFlight = false
    private var lastReq = 0L
    private val t3d0 = SystemClock.uptimeMillis()
    /** Off-screen rendering at a fixed time: ms since this view was made (≥ 0), or -1 for the real clock. Lets the
     *  widget pre-render a repeatable idle loop. */
    var clockMs: Long = -1
    private fun clock() = if (clockMs >= 0) t3d0 + clockMs else SystemClock.uptimeMillis()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect3d = RectF()
    private companion object { val loader: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "avatar-decode").apply { priority = Thread.NORM_PRIORITY - 1 } } }
    private val bgRect = RectF()
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (surface == "home" || surface == "bubble") Backgrounds.soundUser(context, true) }

    private var shownProfile: String? = null
    fun refresh3d() {
        val id = prefs.id; shownProfile = id
        mesh3d = Avatar3DFiles.mesh(context, id).takeIf { it.length() > 0 && (force3d ?: prefs.show3d) }
        if (mesh3d == null) frame3d = null
        lastReq = 0; invalidate()
    }

    var state: Ui = Ui.IDLE
        set(v) { field = v; stateSince = clock(); invalidate() }
    var glowColor: Int = 0xFF4FC3F7.toInt()
    private var stateSince = SystemClock.uptimeMillis()
    private var image: Drawable? = null
    private var cutDrawable: android.graphics.drawable.BitmapDrawable? = null
    /** Off-screen loop rendering only: wait for the background's shared decode instead of skipping it. */
    var waitForBackground = false
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    // ---- talking / body animation ----
    private val prefs = app.belle.assistant.Prefs(ctx, profileId)
    private var still: android.graphics.Bitmap? = null
    private var stillKey: String? = null
    // the picture with its own background removed, used while a custom background is chosen (see [Cutout])
    private var cut: android.graphics.Bitmap? = null; private var cutOf: android.graphics.Bitmap? = null; @Volatile private var cutting = false
    private fun wantCut() = Backgrounds.file(context, prefs) != null
    private fun displayStill(): android.graphics.Bitmap? {
        val s = still ?: return null
        if (!wantCut()) return s
        if (cutOf === s) cut?.let { return it }
        Cutout.init(context)
        if (syncRender || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { cut = Cutout.of(s, stillKey); cutOf = s; return cut }
        if (!cutting) { cutting = true; val k = stillKey
            loader.execute { val c = runCatching { Cutout.of(s, k) }.getOrDefault(s); post { cutting = false; if (still === s) { cut = c; cutOf = s; invalidate() } } } }
        return s
    }
    private var skin: app.belle.assistant.core.Skin2D? = null
    private val pose = app.belle.assistant.core.Pose2D()
    private val driver = app.belle.assistant.core.PoseDriver()
    private var verts = FloatArray(0)
    private var lastFrame = 0L
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var mouthColor = 0xFF3A0A10.toInt()

    /** Re-read the rig (after the rig editor saves). */
    fun reloadRig() { still?.let { buildSkin(it) }; invalidate() }

    private class Built(val skin: app.belle.assistant.core.Skin2D, val mouthColor: Int)

    private fun computeSkin(b: android.graphics.Bitmap): Built {
        val rig = RigStore.rigFor(context, prefs, b)
        val opaque = !b.hasAlpha() || b.getPixel(1, 1) ushr 24 > 200
        val sk = app.belle.assistant.core.Skin2D(rig, b.width.toFloat(), b.height.toFloat(), opaque = opaque)
        // mouth interior colour: darkened average around the mouth line
        val m = rig.mouth
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (dx in -3..3) { val x = ((m.cx + dx * m.w / 8) * b.width).toInt().coerceIn(0, b.width - 1)
            val y = ((m.cy + m.h) * b.height).toInt().coerceIn(0, b.height - 1); val c = b.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++ }
        return Built(sk, Color.rgb(r / n / 4 + 20, g / n / 6, bl / n / 6 + 8))
    }
    private fun applySkin(x: Built?) { skin = x?.skin; verts = FloatArray(x?.skin?.rest?.size ?: 0); x?.let { mouthColor = it.mouthColor } }
    private fun buildSkin(b: android.graphics.Bitmap) = applySkin(computeSkin(b))

    private class Decoded(val image: Drawable?, val still: android.graphics.Bitmap?, val built: Built?, val key: String)
    private var loadSeq = 0

    /** Everything heavy about a new picture: decoding, rigging (may run a face detector), skin weights. */
    private fun decode(file: File?): Decoded {
        val img = try {
            if (file != null && file.exists()) ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // reliable alpha in overlay windows
            } else context.getDrawable(R.drawable.avatar_default)
        } catch (e: Exception) { context.getDrawable(R.drawable.avatar_default) }
        val isGif = img is AnimatedImageDrawable
        // A still bitmap for rigging: PNG, or the GIF's first frame when the user picked "still".
        val still = runCatching {
            if (isGif && prefs.avatarGifMode != "still") null
            else if (file != null && file.exists()) ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            else android.graphics.BitmapFactory.decodeResource(resources, R.drawable.avatar_default)
        }.getOrNull()
        val built = if (still != null) runCatching { computeSkin(still) }.getOrNull()
            else if (isGif && prefs.avatarMouth) runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(file!!)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }
                .getOrNull()?.let { runCatching { computeSkin(it) }.getOrNull() }
            else null
        return Decoded(img, still, built, file?.takeIf { it.exists() }?.let { "${it.path}|${it.lastModified()}" } ?: "default")
    }

    private fun apply(d: Decoded) {
        (image as? AnimatedImageDrawable)?.stop()
        image = d.image; still = d.still; stillKey = d.key; cut = null; cutOf = null
        (image as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
        image?.callback = this
        if (still != null) (image as? AnimatedImageDrawable)?.stop()
        applySkin(d.built)
        refresh3d(); invalidate()
    }

    /**
     * Show a picture. On the UI thread the decoding and rigging happen on a background thread (the old picture stays
     * until the new one is ready), so opening the app never stalls the main thread; off the UI thread (widget
     * frames) it's done right away.
     */
    fun setImageFile(file: File?) {
        val seq = ++loadSeq
        if (syncRender || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { apply(decode(file)); return }
        if (image == null && still == null) refresh3d()
        loader.execute {
            val d = decode(file)
            post { if (seq == loadSeq) apply(d) }
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === image || super.verifyDrawable(who)

    override fun onDetachedFromWindow() { (image as? AnimatedImageDrawable)?.stop(); if (surface == "home" || surface == "bubble") Backgrounds.soundUser(context, false); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (shownProfile != null && shownProfile != prefs.id) refresh3d()   // a different assistant is answering now: never keep the old one's model
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2
        val inner = r * 0.78f
        val t = (clock() - stateSince) / 1000.0
        val color = when (state) {
            Ui.LISTENING -> glowColor
            Ui.WAKING -> 0xFFB39DDB.toInt()
            Ui.THINKING -> 0xFFFFB74D.toInt()
            Ui.SPEAKING -> 0xFF81C784.toInt()
            Ui.SLEEPING -> 0xFF7E57C2.toInt()
            Ui.IDLE -> Color.TRANSPARENT
        }
        if (state != Ui.IDLE && !fill) {
            val pulse = when (state) {
                Ui.LISTENING -> 0.75 + 0.25 * sin(t * 2 * PI * 1.2)
                Ui.SPEAKING -> 0.7 + 0.3 * sin(t * 2 * PI * 4.0) * sin(t * 2 * PI * 0.7)
                Ui.SLEEPING -> maxOf(0.0, 1.0 - t / 1.5)   // ring fades away

                else -> 0.8
            }.toFloat()
            val glowR = inner + (r - inner) * pulse
            glowPaint.shader = RadialGradient(cx, cy, glowR,
                intArrayOf(withAlpha(color, 0), withAlpha(color, 0), withAlpha(color, 170), withAlpha(color, 0)),
                floatArrayOf(0f, inner / glowR * 0.85f, inner / glowR, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, glowR, glowPaint)
            ringPaint.color = color
            ringPaint.strokeWidth = r * 0.06f
            arc.set(cx - inner, cy - inner, cx + inner, cy + inner)
            if (prefs.framing(surface).shape != "circle" && state != Ui.THINKING && state != Ui.WAKING) {
                ringPaint.alpha = (255 * pulse).toInt().coerceIn(0, 255)
                c.drawRoundRect(arc, inner * 0.36f, inner * 0.36f, ringPaint)
            } else if (state == Ui.THINKING || state == Ui.WAKING) {
                val start = ((t * 360) % 360).toFloat()
                c.drawArc(arc, start, 100f, false, ringPaint)
                c.drawArc(arc, start + 180f, 100f, false, ringPaint)
            } else {
                ringPaint.alpha = (255 * pulse).toInt().coerceIn(0, 255)
                c.drawCircle(cx, cy, inner, ringPaint)
            }
        }
        val animate = prefs.avatarAnimate
        val now = clock()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f); lastFrame = now
        val st = when (state) { Ui.LISTENING, Ui.WAKING -> 1; Ui.THINKING -> 2; Ui.SPEAKING -> 3; else -> 0 }
        val amp = if (state == Ui.SPEAKING) LipSync.level() else 0f
        driver.step(dt, st, amp, pose, gentle = if (animate) 1f else 0f)
        if (!prefs.avatarMouth) pose.jaw = 0f
        val sk = skin; val bmp = displayStill()
        val fr = prefs.framing(surface)
        // frame: a square inside the ring, or (fill = widget) the whole view, circle staying round
        val side = if (fill) minOf(w, h) else inner * 2 * 0.92f
        val fw = if (fill && fr.shape != "circle") w else side; val fh = if (fill && fr.shape != "circle") h else side
        val fl = cx - fw / 2; val ft = cy - fh / 2
        c.save()
        when (fr.shape) {
            "circle" -> { clipPath.reset(); clipPath.addCircle(cx, cy, side / 2, android.graphics.Path.Direction.CW); c.clipPath(clipPath) }
            "rounded" -> { clipPath.reset(); clipPath.addRoundRect(fl, ft, fl + fw, ft + fh, side * 0.18f, side * 0.18f, android.graphics.Path.Direction.CW); c.clipPath(clipPath) }
        }
        // per-profile looping background (still / GIF / AVIF / video frames), under the avatar, inside the frame
        val bgF = if (waitForBackground) Backgrounds.frameBlocking(context, prefs, now - t3d0) else Backgrounds.frame(context, prefs, now - t3d0)
        if (bgF != null) { bgRect.set(fl, ft, fl + fw, ft + fh); Backgrounds.drawCover(c, bgF, bgRect, bmpPaint) }
        if (!syncRender && isShown && Backgrounds.file(context, prefs) != null && (bgF == null || Backgrounds.animated(context, prefs)))
            postInvalidateDelayed(if (bgF == null) 250L else 1000L / app.belle.assistant.core.Detail3D(prefs.detailFor(surface), surface).fps(state != Ui.IDLE).coerceIn(1, 12))
        val m3 = mesh3d
        if (m3 != null) {
            val det = app.belle.assistant.core.Detail3D(prefs.detailFor(surface), surface)
            val fps = det.fps(state != Ui.IDLE).coerceAtLeast(1)
            val tSec = (now - t3d0) / 1000f
            val req = { Avatar3DFiles.request(context, prefs, surface, fw.toInt().coerceAtLeast(16), fh.toInt().coerceAtLeast(16), tSec, st, if (prefs.avatarMouth) amp else 0f) }
            if (syncRender) Avatar3DRenderer.renderBlocking(req())?.let { frame3d = it.bitmap; frameSmooth = it.stats.smooth }
            else if (!inFlight && now - lastReq >= 1000L / fps) {
                inFlight = true; lastReq = now; val r = req()
                Avatar3DRenderer.handler.post {
                    val f = Avatar3DRenderer.renderBlocking(r)
                    if (f != null) { frame3d = f.bitmap; frameSmooth = f.stats.smooth }
                    inFlight = false; postInvalidate()
                }
            }
            frame3d?.let { b ->
                bmpPaint.isFilterBitmap = frameSmooth
                bmpPaint.alpha = if (state == Ui.SLEEPING) (255 * (0.45 + 0.55 * minOf(1.0, t / 1.5))).toInt() else 255
                rect3d.set(fl, ft, fl + fw, ft + fh); c.drawBitmap(b, null, rect3d, bmpPaint)
            }
            c.restore()
            if (!syncRender && isShown) postInvalidateDelayed(1000L / fps)
            return
        }
        if (sk != null && bmp != null && (animate || prefs.avatarMouth)) {
            val pl = fr.place(bmp.width.toFloat(), bmp.height.toFloat(), fw, fh)
            val s = pl[2] / bmp.width
            c.save()
            c.translate(fl + pl[0], ft + pl[1])
            c.scale(s, s)
            sk.deform(pose, verts)
            meshPaint.alpha = if (state == Ui.SLEEPING) (255 * (0.45 + 0.55 * minOf(1.0, t / 1.5))).toInt() else 255
            c.drawBitmapMesh(bmp, sk.cols, sk.rows, verts, 0, null, 0, meshPaint)
            drawMouth(c, sk, bmp.width.toFloat(), bmp.height.toFloat())
            c.restore()
        } else (if (image !is AnimatedImageDrawable && wantCut()) displayStill()?.let { b -> (cutDrawable?.takeIf { it.bitmap === b } ?: android.graphics.drawable.BitmapDrawable(resources, b).also { cutDrawable = it }) } ?: image else image)?.let { d ->
            // fit inside the ring, keep aspect ratio and transparency
            val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 1
            val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 1
            val pl = fr.place(iw.toFloat(), ih.toFloat(), fw, fh)
            val bob = if (state == Ui.SPEAKING && !animate) (sin(t * 2 * PI * 3.0) * r * 0.03).toFloat() else 0f
            d.setBounds((fl + pl[0]).toInt(), (ft + pl[1] + bob).toInt(), (fl + pl[0] + pl[2]).toInt(), (ft + pl[1] + pl[3] + bob).toInt())
            // Sleeping: image dims briefly, a small "z z" drifts up.
            d.alpha = if (state == Ui.SLEEPING) (255 * (0.45 + 0.55 * minOf(1.0, t / 1.5))).toInt() else 255
            d.draw(c)
            // GIF playing as-is: mouth overlay only
            if (sk != null && prefs.avatarMouth && pose.jaw > 0.02f) {
                c.save(); c.translate(d.bounds.left.toFloat(), d.bounds.top.toFloat())
                c.scale(d.bounds.width() / sk.w, d.bounds.height() / sk.h)
                pose.sway = 0f; pose.head = 0f; pose.nod = 0f; pose.breathe = 0f
                sk.deform(pose, verts); drawMouth(c, sk, sk.w, sk.h); c.restore()
            }
        }
        c.restore()  // frame clip
        if (state == Ui.SLEEPING && t < 1.6) {
            zPaint.color = withAlpha(0xFF7E57C2.toInt(), (255 * (1 - t / 1.6)).toInt().coerceIn(0, 255))
            zPaint.textSize = r * 0.32f
            c.drawText("z", cx + r * 0.35f, cy - r * 0.2f - (t * r * 0.25).toFloat(), zPaint)
            zPaint.textSize = r * 0.22f
            c.drawText("z", cx + r * 0.6f, cy - r * 0.45f - (t * r * 0.25).toFloat(), zPaint)
        }
        if (state != Ui.IDLE || (animate && skin != null && isShown)) {
            // idle breathing doesn't need 60 fps
            if (state == Ui.IDLE) postInvalidateDelayed(66) else postInvalidateOnAnimation()
        }
    }

    private fun drawMouth(c: Canvas, sk: app.belle.assistant.core.Skin2D, w: Float, h: Float) {
        val open = pose.jaw; if (open < 0.03f) return
        val m = sk.rig.mouth
        val ctr = sk.mouthCenter(pose)
        val hw = m.w * w / 2; val oh = open * m.h * h * 1.4f
        c.save(); c.rotate(pose.head / app.belle.assistant.core.Skin2D.DEG + pose.sway / app.belle.assistant.core.Skin2D.DEG, ctr.x, ctr.y)
        mouthPaint.color = mouthColor
        arc.set(ctr.x - hw * (0.75f + 0.25f * open), ctr.y - oh * 0.15f, ctr.x + hw * (0.75f + 0.25f * open), ctr.y + oh)
        c.drawOval(arc, mouthPaint)
        // tongue hint
        mouthPaint.color = 0xFFB5475A.toInt()
        arc.set(ctr.x - hw * 0.45f, ctr.y + oh * 0.45f, ctr.x + hw * 0.45f, ctr.y + oh * 0.95f)
        if (open > 0.35f) c.drawOval(arc, mouthPaint)
        c.restore()
    }

    private val clipPath = android.graphics.Path()
    private val zPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}
