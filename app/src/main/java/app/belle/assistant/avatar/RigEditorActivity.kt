package app.belle.assistant.avatar

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.belle.assistant.Prefs
import app.belle.assistant.R
import app.belle.assistant.core.MouthBox
import app.belle.assistant.core.P
import app.belle.assistant.core.Rig2D
import app.belle.assistant.service.AssistantService
import java.io.File
import kotlin.math.hypot

/**
 * "Place mouth & joints": drag the dots (head top, neck, hips, shoulders,
 * elbows, wrists) and the mouth box (drag middle to move, corner to resize).
 * Saved per avatar picture, per assistant.
 */
class RigEditorActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var editor: Editor

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val bmp = loadStill()
        val rig = Rig2D.parse(prefs.rig(prefs.avatarKey)) ?: if (prefs.avatarKey == "default") Rig2D.DRAGON else if (prefs.avatarFile == "avatar_boob_dragon.webp") Rig2D.BOOB_DRAGON else RigStore.autoRig(bmp)
        editor = Editor(this, bmp, rig)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1C1B1F.toInt()) }
        root.addView(TextView(this).apply {
            text = "Drag the dots onto the joints, and the pink box onto the mouth (corner = resize)."
            setTextColor(Color.WHITE); textSize = 17f; setPadding(32, 48, 32, 16)
        })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 8, 8, 32) }
        fun btn(t: String, f: () -> Unit) = row.addView(Button(this).apply { text = t; textSize = 16f; setOnClickListener { f() } })
        btn("Auto-place") { editor.rig = if (prefs.avatarKey == "default") Rig2D.DRAGON else if (prefs.avatarFile == "avatar_boob_dragon.webp") Rig2D.BOOB_DRAGON else RigStore.autoRig(bmp); editor.invalidate() }
        btn("Test mouth") { editor.talkTest() }
        // wings / tail / legs: extra parts for full-body pictures; tap again to remove them
        btn("Wings, tail, legs") {
            val r = editor.rig
            editor.rig = if (r.extras.isNotEmpty()) r.copy(joints = r.joints - Rig2D.EXTRA.toSet())
                else r.copy(joints = r.joints + Rig2D.EXTRA.associateWith { n -> Rig2D.BOOB_DRAGON.j(n) })
            editor.invalidate()
        }
        btn("Save") {
            prefs.setRig(prefs.avatarKey, editor.rig.copy(auto = false).toLine())
            AssistantService.start(this, AssistantService.ACTION_AVATAR)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish()
        }
        root.addView(row)
        setContentView(root)
    }

    private fun loadStill(): Bitmap {
        val f = prefs.avatarFile.takeIf { it.isNotEmpty() }?.let { File(filesDir, it) }
        return runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f!!)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }
            .getOrElse { BitmapFactory.decodeResource(resources, R.drawable.avatar_default) }
    }

    private class Editor(ctx: Context, val bmp: Bitmap, var rig: Rig2D) : View(ctx) {
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
}
