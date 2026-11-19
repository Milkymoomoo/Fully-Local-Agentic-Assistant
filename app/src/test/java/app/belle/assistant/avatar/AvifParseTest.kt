package app.belle.assistant.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvifParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream(n).readBytes()

    @Test fun animatedAvifTrack() {
        val d = res("t_anim.avif")
        assertTrue(Avif.isAvif(d))
        val s = Avif.sequence(d)
        assertNotNull(s); s!!
        assertEquals(30, s.samples.size)                  // 3 s at 10 fps
        assertTrue(s.durMs.all { it in 90..110 })
        assertNotNull(s.av1C)
        assertTrue(s.samples.all { (it[0].toInt() and 0x80) == 0 && ((it[0].toInt() shr 3) and 15) in listOf(1, 3, 4, 5, 6) })   // a valid first OBU (temporal delimiters are stripped in ISO-BMFF)
    }

    @Test fun stillAvifHasNoTrack() { assertNull(Avif.sequence(res("t.avif"))) }
}
