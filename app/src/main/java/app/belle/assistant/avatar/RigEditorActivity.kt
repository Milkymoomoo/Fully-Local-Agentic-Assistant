package app.belle.assistant.avatar

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.belle.assistant.Prefs
import app.belle.assistant.service.AssistantService

/**
 * "Place mouth & joints": drag the dots (head top, neck, hips, shoulders,
 * elbows, wrists) and the mouth box (drag middle to move, corner to resize).
 * Saved per avatar picture, per assistant.
 */
class RigEditorActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var editor: RigCanvas

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val bmp = EditorStill.load(this, prefs)
        editor = RigCanvas(this, bmp, EditorStill.rig(prefs, bmp))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1C1B1F.toInt()) }
        root.addView(TextView(this).apply {
            text = "Drag the dots onto the joints, and the pink box onto the mouth (corner = resize)."
            setTextColor(Color.WHITE); textSize = 17f; setPadding(32, 48, 32, 16)
        })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 8, 8, 32) }
        fun btn(t: String, f: () -> Unit) = row.addView(Button(this).apply { text = t; textSize = 16f; setOnClickListener { f() } })
        btn("Auto-place") { editor.rig = EditorStill.autoRig(prefs, bmp); editor.invalidate() }
        btn("Test mouth") { editor.talkTest() }
        // wings / tail / legs: extra parts for full-body pictures; tap again to remove them
        btn("Wings, tail, legs") { editor.rig = EditorStill.toggleExtras(editor.rig); editor.invalidate() }
        btn("Save") {
            prefs.setRig(prefs.avatarKey, editor.rig.copy(auto = false).toLine())
            AssistantService.start(this, AssistantService.ACTION_AVATAR)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish()
        }
        root.addView(row)
        setContentView(root)
    }

}
