package app.belle.desktop

import app.belle.assistant.ui.AssistantWidget
import app.belle.assistant.ui.NameArt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The widget's name art renders on the desktop graphics shims (every style draws visible pixels). */
class NameArtTest {
    @Test fun stylesDraw() {
        for (style in NameArt.STYLES.map { it.first }) {
            val b = AssistantWidget.trim(NameArt.render(DesktopContext, "Bob From Accounting", style, "sans", 400, 40f, phase = 0.3f, maxLines = 1))
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val visible = px.count { (it ushr 24) > 40 }
            System.getProperty("desk.nameart.out")?.let { d -> java.io.File(d).mkdirs(); java.io.File(d, "$style.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(org.jetbrains.skia.Bitmap().also { it.allocN32Pixels(1, 1) }).let { b.skImage() }.encodeToData()!!.bytes) }
            assertTrue(visible > 200, "style $style drew $visible visible pixels (${b.width}x${b.height})")
        }
    }
}
