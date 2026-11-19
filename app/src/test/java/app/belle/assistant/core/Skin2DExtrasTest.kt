package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class Skin2DExtrasTest {
    private fun moveAt(sk: Skin2D, pose: Pose2D, x: Float, y: Float): Float {
        val out = FloatArray(sk.rest.size); sk.deform(pose, out)
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0 until sk.rest.size / 2) { val d = hypot(sk.rest[2 * i] - x, sk.rest[2 * i + 1] - y); if (d < bd) { bd = d; best = i } }
        return hypot(out[2 * best] - sk.rest[2 * best], out[2 * best + 1] - sk.rest[2 * best + 1])
    }

    @Test fun roundTripKeepsExtras() {
        val r = Rig2D.parse(Rig2D.BOOB_DRAGON.toLine())!!
        assertEquals(Rig2D.EXTRA.toSet(), r.extras.toSet())
        assertTrue(Rig2D.parse(Rig2D.DRAGON.toLine())!!.extras.isEmpty())
    }

    @Test fun wingTailAndLegsMoveTheirOwnParts() {
        val sk = Skin2D(Rig2D.BOOB_DRAGON, 1000f, 1000f, 40, 40, opaque = true)
        val wing = Pose2D(wingR = 12f * Skin2D.DEG)
        assertTrue("right wing tip moves", moveAt(sk, wing, 780f, 470f) > 8f)
        assertTrue("head stays", moveAt(sk, wing, 500f, 150f) < 3f)
        assertTrue("background corner stays", moveAt(sk, wing, 0f, 0f) < 1f)
        val tail = Pose2D(tail = 12f * Skin2D.DEG)
        assertTrue("tail tip moves", moveAt(sk, tail, 770f, 760f) > 8f)
        val leg = Pose2D(legL = 8f * Skin2D.DEG)
        assertTrue("left foot moves", moveAt(sk, leg, 440f, 920f) > 3f)
    }

    @Test fun driverAnimatesExtras() {
        val d = PoseDriver(); val p = Pose2D(); var maxW = 0f; var maxT = 0f
        repeat(200) { d.step(0.05f, 3, 0.6f, p); maxW = maxOf(maxW, kotlin.math.abs(p.wingL)); maxT = maxOf(maxT, kotlin.math.abs(p.tail)) }
        assertTrue(maxW > 2f * Skin2D.DEG); assertTrue(maxT > 2f * Skin2D.DEG)
    }
}
