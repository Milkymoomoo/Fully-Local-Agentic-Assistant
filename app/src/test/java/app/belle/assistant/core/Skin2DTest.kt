package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Skin2DTest {
    @Test fun restPoseIsIdentity() {
        val s = Skin2D(Rig2D.DRAGON, 512f, 512f)
        val out = FloatArray(s.rest.size)
        s.deform(Pose2D(), out)
        for (i in out.indices) assertEquals(s.rest[i], out[i], 0.05f)
    }

    @Test fun jawOpensOnlyUnderTheMouth() {
        val s = Skin2D(Rig2D.DRAGON, 512f, 512f)
        val out = FloatArray(s.rest.size)
        s.deform(Pose2D(jaw = 1f), out)
        var moved = 0
        for (i in 0 until out.size / 2) {
            val dy = out[2 * i + 1] - s.rest[2 * i + 1]
            if (s.rest[2 * i + 1] < Rig2D.DRAGON.mouth.cy * 512f - 1) assertEquals(0f, dy, 0.05f)   // above the mouth: still
            if (dy > 1f) { moved++; assertTrue(abs(s.rest[2 * i] - 256f) < 512f * 0.25f) }        // near the mouth horizontally
        }
        assertTrue("jaw should move some vertices", moved > 0)
    }

    @Test fun headTiltMovesHeadMoreThanBody() {
        val s = Skin2D(Rig2D.DRAGON, 512f, 512f)
        val out = FloatArray(s.rest.size)
        s.deform(Pose2D(head = 10 * Skin2D.DEG), out)
        fun disp(x: Int, y: Int): Float { val i = (y * 20 / 512) * 21 + x * 20 / 512; return abs(out[2 * i] - s.rest[2 * i]) + abs(out[2 * i + 1] - s.rest[2 * i + 1]) }
        assertTrue(disp(256, 128) > 10f)          // forehead swings
        assertTrue(disp(256, 460) < 2f)           // chest stays
    }

    @Test fun autoRigFindsNeckOnSilhouette() {
        // head circle on top of a wide body: neck should be at the narrow gap
        val w = 100; val h = 100
        val m = BooleanArray(w * h) { i -> val x = i % w; val y = i / w
            ((x - 50) * (x - 50) + (y - 25) * (y - 25) < 400) || (y in 45..47 && x in 45..55) || (y >= 48 && x in 15..85) }
        val r = Rig2D.auto(m, w, h)
        assertTrue("neck y ${r.j("neck").y}", r.j("neck").y in 0.43f..0.49f)
        assertTrue(r.mouth.cy in 0.30f..0.46f)
        assertEquals(0.5f, r.mouth.cx, 0.03f)
    }

    @Test fun rigRoundTrips() {
        val r = Rig2D.parse(Rig2D.DRAGON.toLine())!!
        assertEquals(Rig2D.DRAGON.mouth, r.mouth); assertEquals(Rig2D.DRAGON.joints, r.joints)
    }

    @Test fun driverOpensJawOnlyWhileSpeaking() {
        val d = PoseDriver(); val p = Pose2D()
        d.step(0.02f, 1, 0.8f, p); assertEquals(0f, p.jaw, 0f)
        d.step(0.02f, 3, 0.5f, p); assertTrue(p.jaw > 0.5f)
    }
}

class FramingTest {
    @Test fun zoomOneShowsWholePicture() {
        val p = Framing().place(768f, 768f, 200f, 200f)
        assertEquals(0f, p[0], 0.01f); assertEquals(0f, p[1], 0.01f); assertEquals(200f, p[2], 0.01f)
        val wide = Framing().place(768f, 768f, 400f, 200f)   // wide widget: letterboxed, nothing cut
        assertEquals(100f, wide[0], 0.01f); assertEquals(200f, wide[3], 0.01f)
    }
    @Test fun zoomAndPan() {
        val p = Framing(zoom = 2f, cx = 0.5f, cy = 0.3f).place(100f, 100f, 100f, 100f)
        assertEquals(200f, p[2], 0.01f); assertEquals(-50f, p[0], 0.01f); assertEquals(-10f, p[1], 0.01f)
        assertEquals(Framing(shape = "circle", zoom = 1.5f), Framing.parse(Framing(shape = "circle", zoom = 1.5f).toLine()))
    }
}

class NamesTest {
    @Test fun bobDefaults() {
        assertEquals(listOf("hey bob", "okay bob", "hey bob from accounting"), WakeWord.defaultPhrases("Bob From Accounting"))
        assertEquals(listOf("hey belle", "okay belle"), WakeWord.defaultPhrases("Belle"))
        assertEquals("bob", WakeWord.callName("Bob From Accounting"))
    }
}
