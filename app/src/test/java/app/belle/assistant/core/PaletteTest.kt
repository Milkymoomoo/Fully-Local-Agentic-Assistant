package app.belle.assistant.core

import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun check(name: String, p: Map<String, Long>) {
        val bad = Palette.TEXT_PAIRS.mapNotNull { (fg, bg) ->
            val r = Palette.contrast(p.getValue(fg), p.getValue(bg)); if (r < 4.5) "$fg on $bg = ${"%.2f".format(r)}" else null }
        assertTrue("$name below WCAG AA: $bad", bad.isEmpty())
    }
    @Test fun darkMeetsAA() = check("dark", Palette.DARK)
    @Test fun lightMeetsAA() = check("light", Palette.LIGHT)
    @Test fun widgetAndOverlayColours() {
        val widgetBg = 0xFF2A1A2E  // widget card (alpha 0.9 over any wallpaper; tested opaque)
        assertTrue(Palette.contrast(0xFFFFFFFF, widgetBg) >= 4.5)
        assertTrue(Palette.contrast(0xFFE0D6E3, widgetBg) >= 4.5)  // state line
        assertTrue(Palette.contrast(0xFFFFFFFF, 0xFFB3261E) >= 4.5) // selected chip
        assertTrue(Palette.contrast(0xFFFFFFFF, 0xFF4A3A4E) >= 4.5) // unselected chip
    }
}
