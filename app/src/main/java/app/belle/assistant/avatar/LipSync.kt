package app.belle.assistant.avatar

import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Mouth openness from the speech audio actually being played (after the voice
 * slider). Piper via sherpa-onnx doesn't expose phoneme timings in this build, so
 * we use the amplitude envelope: 20 ms RMS frames, normalised to the clip's loud
 * level, with fast attack / slower release so syllables read clearly.
 */
object LipSync {
    private const val FRAME_MS = 20
    @Volatile private var env: FloatArray = FloatArray(0)
    @Volatile private var startedAt = 0L

    fun begin(samples: FloatArray, rate: Int) {
        val n = rate * FRAME_MS / 1000
        val frames = FloatArray(samples.size / n + 1)
        for (f in frames.indices) {
            var s = 0.0; val a = f * n; val b = minOf(samples.size, a + n)
            for (i in a until b) s += samples[i] * samples[i]
            frames[f] = if (b > a) sqrt(s / (b - a)).toFloat() else 0f
        }
        val loud = frames.sortedArray().let { if (it.isEmpty()) 1f else it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }.coerceAtLeast(1e-4f)
        var o = 0f
        env = FloatArray(frames.size) { i ->
            val x = ((frames[i] / loud - 0.08f) / 0.92f).coerceIn(0f, 1f)
            o = if (x > o) o + (x - o) * 0.7f else o + (x - o) * 0.35f
            o
        }
        startedAt = SystemClock.uptimeMillis()
    }

    fun end() { env = FloatArray(0) }

    /** 0 (closed) .. 1 (wide open) right now. */
    fun level(): Float {
        val e = env; if (e.isEmpty()) return 0f
        val i = ((SystemClock.uptimeMillis() - startedAt) / FRAME_MS).toInt()
        return if (i in e.indices) e[i] else 0f
    }
}
