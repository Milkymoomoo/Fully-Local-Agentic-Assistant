package app.belle.assistant.ui

import app.belle.assistant.models.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Downloads page's rows are worked out once, in one pass, off the main thread: installed? at most once per model. */
class ModelsPageDataTest {
    @Test fun everyModelIsCheckedOnceAndGrouped() {
        val cat = Downloads.catalog()
        val asked = mutableMapOf<String, Int>()
        val groups = ModelsPageData.build(cat, 12.0, null) { d -> asked.merge(d.id, 1, Int::plus); d.kind == "stt" }
        assertTrue(groups.isNotEmpty())
        val rows = groups.flatMap { it.rows }
        assertTrue("every row checked once: $asked", asked.values.all { it == 1 })
        assertEquals(rows.map { it.d.id }.toSet().size, asked.size)
        assertTrue(rows.first { it.d.kind == "stt" }.have)
        // every one-tap model except the 3D set (installed from the 3D page) appears
        val shown = rows.map { it.d.id }.toSet()
        val missing = cat.filter { it.kind in setOf("stt", "llm", "voice") && it.id !in shown }.map { it.id }
        assertTrue("not on the page: $missing", missing.isEmpty())
    }

    @Test fun ramFitNeedsNoContext() {
        val big = Downloads.catalog().first { it.minRamGb >= 8 }
        assertEquals(2, Downloads.ramFit(4.0, big).second)
        assertEquals(0, Downloads.ramFit(16.0, big).second)
    }
}
