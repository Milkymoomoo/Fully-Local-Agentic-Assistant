package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTextTest {
    @Test fun splitsStageAndStep() {
        val p = ProgressText.split("Detailed shape 7/12")
        assertEquals("Detailed shape", p.stage); assertEquals(7, p.step); assertEquals(12, p.steps); assertEquals("Step 7 of 12", p.stepLine)
        assertEquals("Rough shape", ProgressText.split("Rough shape 1/8").stage)
        assertEquals("Downloading", ProgressText.split("Downloading 3/18").stage)
    }
    @Test fun keepsLabelsWithoutAStep() {
        assertEquals("Decoding", ProgressText.split("Decoding").stage); assertNull(ProgressText.split("Decoding").stepLine)
        assertEquals("Building detail levels (12,345 triangles)", ProgressText.split("Building detail levels (12,345 triangles)").stage)
        assertEquals("Starting…", ProgressText.split(null).stage)
        assertNull(ProgressText.split("Odd 9/3").step)          // impossible step: shown as written
    }
    @Test fun percentNeverSays100BeforeDone() {
        assertEquals("99 %", ProgressText.percent(0.999f)); assertEquals("100 %", ProgressText.percent(1f)); assertEquals("0 %", ProgressText.percent(-1f))
    }
}
