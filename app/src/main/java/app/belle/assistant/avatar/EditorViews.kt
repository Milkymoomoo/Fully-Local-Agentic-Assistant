package app.belle.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.belle.assistant.Prefs
import app.belle.assistant.R
import app.belle.assistant.core.Framing
import app.belle.assistant.core.P
import app.belle.assistant.core.Rig2D
import java.io.File
import kotlin.math.hypot

/*
 * The framing and rig editors' drawing surfaces, shared by the phone's editor screens and the Windows program's
 * editor windows (which put their own controls around them).
 */

object EditorStill {
    /** The active assistant's picture at full size (the built-in one if none is set or it can't be read). */
    fun load(ctx: Context, prefs: Prefs): Bitmap {
        val f = prefs.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) }
        return runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f!!)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }
            .getOrElse { BitmapFactory.decodeResource(ctx.resources, R.drawable.avatar_default)!! }
    }
    /** "Auto-place": the built-in pictures' own rigs, otherwise the automatic rig for this picture. */
    fun autoRig(prefs: Prefs, bmp: Bitmap): Rig2D =
        if (prefs.avatarKey == "default") Rig2D.DRAGON else if (prefs.avatarFile == "avatar_boob_dragon.webp") Rig2D.BOOB_DRAGON else RigStore.autoRig(bmp)
    /** The saved rig, or the automatic one. */
    fun rig(prefs: Prefs, bmp: Bitmap): Rig2D = Rig2D.parse(prefs.rig(prefs.avatarKey)) ?: autoRig(prefs, bmp)
    /** "Wings, tail, legs": adds the extra parts for full-body pictures, or removes them. */
    fun toggleExtras(r: Rig2D): Rig2D = if (r.extras.isNotEmpty()) r.copy(joints = r.joints - Rig2D.EXTRA.toSet())
        else r.copy(joints = r.joints + Rig2D.EXTRA.associateWith { n -> Rig2D.BOOB_DRAGON.j(n) })
}

/** The framing editor's canvas: the picture with the frame shape cut out; drag to move, pinch (or [zoomBy]) to zoom. */
class FramingCanvas(ctx: Context, val bmp: Bitmap, val onChange: (Framing) -> Unit) : View(ctx) {
    var framing = Framing(); var surface = "home"
    private val dim = Paint().apply { color = 0xB0000000.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFD2BCFF.toInt() }
    private val path = Path(); private val frame = RectF(); private val dst = RectF()
    private var lastX = 0f; private var lastY = 0f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            framing = framing.copy(zoom = (framing.zoom * d.scaleFactor).coerceIn(0.5f, 5f)); onChange(framing); invalidate(); return true
        }
    })

    /** Zoom without a pinch (the Windows program's mouse wheel). */
    fun zoomBy(f: Float) { framing = framing.copy(zoom = (framing.zoom * f).coerceIn(0.5f, 5f)); onChange(framing); invalidate() }

    override fun onDraw(c: Canvas) {
        val side = minOf(width, height) * 0.78f
        frame.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
        val pl = framing.place(bmp.width.toFloat(), bmp.height.toFloat(), side, side)
        dst.set(frame.left + pl[0], frame.top + pl[1], frame.left + pl[0] + pl[2], frame.top + pl[1] + pl[3])
        c.drawColor(0xFF221E25.toInt())
        c.drawBitmap(bmp, null, dst, null)
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        when (framing.shape) {
            "circle" -> path.addCircle(frame.centerX(), frame.centerY(), side / 2, Path.Direction.CW)
            "rounded" -> path.addRoundRect(frame, side * 0.18f, side * 0.18f, Path.Direction.CW)
            else -> path.addRect(frame, Path.Direction.CW)
        }
        c.drawPath(path, dim)
        when (framing.shape) {
            "circle" -> c.drawCircle(frame.centerX(), frame.centerY(), side / 2, edge)
            "rounded" -> c.drawRoundRect(frame, side * 0.18f, side * 0.18f, edge)
            else -> c.drawRect(frame, edge)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                val w = dst.width().coerceAtLeast(1f); val h = dst.height().coerceAtLeast(1f)
                framing = framing.copy(cx = (framing.cx - (e.x - lastX) / w).coerceIn(0f, 1f), cy = (framing.cy - (e.y - lastY) / h).coerceIn(0f, 1f))
                lastX = e.x; lastY = e.y; onChange(framing); invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.getX(0); lastY = e.getY(0) }
        }
        return true
    }
}

/** The rig editor's canvas: drag the joint dots and the mouth box (corner = resize); [talkTest] opens the mouth. */
class RigCanvas(ctx: Context, val bmp: Bitmap, var rig: Rig2D) : View(ctx) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; color = 0xCCFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFFF4FA3.toInt() }
    private val img = RectF()
    private var drag: String? = null
    private var talkUntil = 0L
    private val bones = listOf("hips" to "neck", "neck" to "headTop", "shoulderL" to "elbowL", "elbowL" to "wristL", "shoulderR" to "elbowR", "elbowR" to "wristR", "neck" to "shoulderL", "neck" to "shoulderR")

    fun talkTest() { talkUntil = System.currentTimeMillis() + 2500; invalidate() }

    private fun sx(x: Float) = img.left + x * img.width()
    private fun sy(y: Float) = img.top + y * img.height()

    override fun onDraw(c: Canvas) {
        val s = minOf(width * 0.94f / bmp.width, height * 0.94f / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(bmp, null, img, null)
        val extraBones = listOf("wingRootL" to "wingTipL", "wingRootR" to "wingTipR", "tailBase" to "tailTip", "hipL" to "kneeL", "kneeL" to "footL", "hipR" to "kneeR", "kneeR" to "footR").filter { rig.has(it.first) }
        for ((a, b) in bones + extraBones) { val p = rig.j(a); val q = rig.j(b); c.drawLine(sx(p.x), sy(p.y), sx(q.x), sy(q.y), line) }
        for (n in Rig2D.JOINTS + rig.extras) {
            val p = rig.j(n)
            dot.color = if (n == drag) 0xFFFFEB3B.toInt() else 0xFF4FC3F7.toInt()
            c.drawCircle(sx(p.x), sy(p.y), 22f, dot)
            c.drawText(NAMES[n] ?: n, sx(p.x) + 26f, sy(p.y) - 10f, label)
        }
        val m = rig.mouth
        val open = if (System.currentTimeMillis() < talkUntil) (0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 90.0).toFloat()) else 0f
        if (open > 0) { dot.color = 0xFF3A0A10.toInt(); c.drawOval(sx(m.cx - m.w / 2), sy(m.cy), sx(m.cx + m.w / 2), sy(m.cy + m.h * 1.4f * open), dot); postInvalidateOnAnimation() }
        c.drawRect(sx(m.cx - m.w / 2), sy(m.cy - m.h / 2), sx(m.cx + m.w / 2), sy(m.cy + m.h / 2), box)
        dot.color = 0xFFFF4FA3.toInt(); c.drawCircle(sx(m.cx + m.w / 2), sy(m.cy + m.h / 2), 16f, dot)
        c.drawText("mouth", sx(m.cx - m.w / 2), sy(m.cy - m.h / 2) - 10f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val nx = ((e.x - img.left) / img.width()).coerceIn(-0.1f, 1.1f)
        val ny = ((e.y - img.top) / img.height()).coerceIn(-0.1f, 1.1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val m = rig.mouth
                drag = when {
                    hypot(e.x - sx(m.cx + m.w / 2), e.y - sy(m.cy + m.h / 2)) < 50 -> "mouthSize"
                    else -> (Rig2D.JOINTS + rig.extras).minByOrNull { val p = rig.j(it); hypot(e.x - sx(p.x), e.y - sy(p.y)) }
                        ?.takeIf { val p = rig.j(it); hypot(e.x - sx(p.x), e.y - sy(p.y)) < 60 }
                        ?: if (e.x in sx(m.cx - m.w / 2)..sx(m.cx + m.w / 2) && e.y in sy(m.cy - m.h / 2 - 0.03f)..sy(m.cy + m.h / 2 + 0.03f)) "mouth" else null
                }
            }
            MotionEvent.ACTION_MOVE -> when (val d = drag) {
                null -> {}
                "mouth" -> rig = rig.copy(mouth = rig.mouth.copy(cx = nx, cy = ny))
                "mouthSize" -> rig.mouth.let { m -> rig = rig.copy(mouth = m.copy(w = ((nx - m.cx) * 2).coerceIn(0.02f, 0.8f), h = ((ny - m.cy) * 2).coerceIn(0.01f, 0.3f))) }
                else -> rig = rig.copy(joints = rig.joints + (d to P(nx, ny)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = null
        }
        invalidate(); return true
    }

    companion object {
        val NAMES = mapOf("headTop" to "head", "neck" to "neck", "hips" to "hips", "shoulderL" to "shoulder", "elbowL" to "elbow", "wristL" to "wrist",
            "shoulderR" to "shoulder", "elbowR" to "elbow", "wristR" to "wrist")
    }
}
