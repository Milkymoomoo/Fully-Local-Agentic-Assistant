package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceShiftTest {
    private val rate = 22050

    /** Vowel-like test tone: harmonics of f0 shaped by a formant near 700 Hz. */
    private fun vowel(f0: Double, secs: Double = 1.5) = FloatArray((rate * secs).toInt()) { i ->
        val t = i.toDouble() / rate
        var s = 0.0
        var h = 1
        while (h * f0 < 5000) { val fh = h * f0; val amp = 1.0 / (1 + ((fh - 700) / 300).let { it * it }); s += amp * sin(2 * PI * fh * t); h++ }
        (0.3 * s).toFloat()
    }

    private fun pitch(x: FloatArray): Double {   // autocorrelation f0 estimate, 70–400 Hz
        val seg = x.copyOfRange(x.size / 4, x.size / 4 + 4096)
        var best = 0; var bestC = Double.NEGATIVE_INFINITY
        for (lag in rate / 400..rate / 70) {
            var c = 0.0; for (i in 0 until seg.size - lag) c += seg[i] * seg[i + lag]
            if (c > bestC) { bestC = c; best = lag }
        }
        return rate.toDouble() / best
    }

    @Test fun pitchMovesBothWaysAndLengthIsKept() {
        val x = vowel(150.0)
        val up = VoiceShift.process(x, 1f)
        val down = VoiceShift.process(x, -1f)
        assertEquals(x.size, up.size); assertEquals(x.size, down.size)
        val pu = pitch(up); val pd = pitch(down)
        assertTrue("feminine pitch $pu", pu in 175.0..205.0)      // ≈ 189 Hz
        assertTrue("masculine pitch $pd", pd in 108.0..130.0)     // ≈ 119 Hz
    }

    @Test fun neutralIsUntouched() {
        val x = vowel(150.0)
        assertTrue(VoiceShift.process(x, 0f) === x)
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(64) { sin(it * 0.3) }; val im = DoubleArray(64); val orig = re.copyOf()
        VoiceShift.fft(re, im, false); VoiceShift.fft(re, im, true)
        for (i in re.indices) assertEquals(orig[i], re[i], 1e-9)
    }
}
