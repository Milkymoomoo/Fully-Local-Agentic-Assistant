package app.belle.assistant.threed

import org.junit.Assert.assertEquals
import org.junit.Test

class DropFloatersTest {
    @Test fun keepsBodyDropsSpeck() {
        val c = ArrayList<Int>()
        for (x in 4..9) for (y in 4..9) for (z in 4..9) { c += x; c += y; c += z }   // 216-voxel body
        c += 20; c += 20; c += 20                                                     // a lone speck
        for (x in 10..12) { c += x; c += 9; c += 9 }                                  // a thin part touching the body (diagonal ok)
        val out = Pixal3D.dropFloaters(c.toIntArray(), 32)
        assertEquals(216 + 3, out.size / 3)
        assertEquals(Pixal3D.dropFloaters(out, 32).toList(), out.toList())
    }
}
