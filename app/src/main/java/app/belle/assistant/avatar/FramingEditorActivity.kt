package app.belle.assistant.avatar

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.R
import app.belle.assistant.core.Framing
import app.belle.assistant.service.AssistantService
import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.FramingRev
import java.io.File

/**
 * "Framing": how much of the picture (or 3D view) shows on Home, in the bubble and on the widget.
 * Pinch to zoom, drag to move, pick a frame shape. Saved per assistant and per picture.
 */
class FramingEditorActivity : Activity() {
    private lateinit var prefs: Prefs
    private var surface = "home"
    private lateinit var editor: Editor
    private val previews = mutableListOf<View>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val fg = 0xFFE8E0E8.toInt(); val sub = 0xFFCDC3D6.toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF151218.toInt()); addView(col) })
        fun label(t: String, size: Float = 16f, c: Int = fg) = TextView(this).apply { text = t; textSize = size; setTextColor(c); setPadding(0, 16, 0, 8) }.also { col.addView(it) }
        label("Framing for ${prefs.assistantName}", 22f)
        label("Pinch to zoom, drag to move. The bright area is what shows.", 15f, sub)

        val same = CheckBox(this).apply { text = "Use the same framing everywhere"; setTextColor(fg); textSize = 16f; isChecked = prefs.framingSameForAll }
        col.addView(same)
        val surfaces = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Framing.SURFACES.forEachIndexed { i, (id, name) ->
            surfaces.addView(RadioButton(this).apply { text = name; setTextColor(fg); this.id = 100 + i; isChecked = id == surface })
        }
        col.addView(surfaces)
        surfaces.visibility = if (same.isChecked) View.GONE else View.VISIBLE

        editor = Editor(this, loadStill()) { f -> prefs.setFraming(surface, f); refreshPreviews() }
        col.addView(editor, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 0.9).toInt()))

        label("Frame shape")
        val shapes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Framing.SHAPES.forEachIndexed { i, (id, name) -> shapes.addView(RadioButton(this).apply { text = name; setTextColor(fg); textSize = 16f; this.id = 200 + i }) }
        col.addView(shapes)

        // 3D camera (only when this assistant has a 3D avatar)
        val has3d = Avatar3DFiles.has(this, ProfileStore.activeId(this))
        label("3D view" + if (has3d) "" else " — make or import a 3D avatar first", 18f, if (has3d) fg else sub)
        val bars = listOf(
            Triple("Camera distance", 0f..1f, { f: Framing -> (f.dist - 1f) / 3f }) to { f: Framing, v: Float -> f.copy(dist = 1f + v * 3f) },
            Triple("Camera height", 0f..1f, { f: Framing -> (f.height + 1f) / 2f }) to { f: Framing, v: Float -> f.copy(height = v * 2f - 1f) },
            Triple("Camera angle", 0f..1f, { f: Framing -> (f.angle + 180f) / 360f }) to { f: Framing, v: Float -> f.copy(angle = v * 360f - 180f) },
        )
        val seekers = bars.map { (spec, set) ->
            label(spec.first, 15f, if (has3d) fg else sub)
            SeekBar(this).apply {
                max = 1000; isEnabled = has3d; progress = (spec.third(prefs.framing(surface)) * 1000).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { prefs.setFraming(surface, set(prefs.framing(surface), p / 1000f)); refreshPreviews() } }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }.also { col.addView(it) } to spec
        }

        label("Preview")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((id, name) in Framing.SURFACES.take(2)) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(16, 0, 16, 0) }
            val v = AvatarView(this, id).apply { setImageFile(prefs.avatarFile.takeIf { it.isNotEmpty() }?.let { File(filesDir, it) }) }
            c.addView(v, LinearLayout.LayoutParams(260, 260)); c.addView(TextView(this).apply { text = name; setTextColor(sub) })
            previews += v; row.addView(c)
        }
        col.addView(row)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) }
        buttons.addView(Button(this).apply { text = "Show everything"; setOnClickListener { prefs.setFraming(surface, prefs.framing(surface).copy(zoom = 1f, cx = .5f, cy = .5f)); sync(shapes, seekers) } })
        buttons.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        col.addView(buttons)

        fun selectShape() { shapes.check(200 + Framing.SHAPES.indexOfFirst { it.first == prefs.framing(surface).shape }.coerceAtLeast(0)) }
        selectShape()
        shapes.setOnCheckedChangeListener { _, id -> prefs.setFraming(surface, prefs.framing(surface).copy(shape = Framing.SHAPES[id - 200].first)); sync(shapes, seekers) }
        surfaces.setOnCheckedChangeListener { _, id -> surface = Framing.SURFACES[id - 100].first; sync(shapes, seekers) }
        same.setOnCheckedChangeListener { _, on ->
            val cur = prefs.framing(surface); prefs.framingSameForAll = on; prefs.setFraming(surface, cur)
            surfaces.visibility = if (on) View.GONE else View.VISIBLE; sync(shapes, seekers)
        }
        sync(shapes, seekers)
    }

    private fun sync(shapes: RadioGroup, seekers: List<Pair<SeekBar, Triple<String, ClosedFloatingPointRange<Float>, (Framing) -> Float>>>) {
        val f = prefs.framing(surface)
        val want = 200 + Framing.SHAPES.indexOfFirst { it.first == f.shape }.coerceAtLeast(0)
        if (shapes.checkedRadioButtonId != want) shapes.check(want)
        seekers.forEach { (bar, spec) -> bar.progress = (spec.third(f) * 1000).toInt() }
        editor.framing = f; editor.surface = surface; editor.invalidate(); refreshPreviews()
    }

    private fun refreshPreviews() { previews.forEach { it.invalidate() }; FramingRev.rev++ }

    override fun onPause() {
        super.onPause()
        AssistantService.start(this, AssistantService.ACTION_AVATAR)   // bubble + widget redraw
        AssistantWidget.updateAll(this)
    }

    private fun loadStill(): Bitmap {
        val f = prefs.avatarFile.takeIf { it.isNotEmpty() }?.let { File(filesDir, it) }
        return runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f!!)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }
            .getOrElse { BitmapFactory.decodeResource(resources, R.drawable.avatar_default) }
    }

    private class Editor(ctx: Context, val bmp: Bitmap, val onChange: (Framing) -> Unit) : View(ctx) {
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
}
