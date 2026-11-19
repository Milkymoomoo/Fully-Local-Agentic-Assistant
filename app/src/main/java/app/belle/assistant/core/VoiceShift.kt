package app.belle.assistant.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Masculine ↔ feminine voice slider, applied to TTS output (pure Kotlin, on device).
 *
 * amount -1 … +1 → pitch ±4 semitones and formants (vocal-tract size) ∓/±10 %.
 * Pitch and formants are controlled separately, so it doesn't sound "chipmunk":
 *  1. WSOLA time-stretch by the pitch ratio, then resample back to the original
 *     length → pitch × p (but formants also × p).
 *  2. Per STFT frame, a cepstrally-smoothed spectral envelope is measured and
 *     the frame is re-shaped so the envelope ends up scaled by the formant
 *     ratio f instead of p (envelope warp), keeping phase.
 */
object VoiceShift {
    data class Params(val pitch: Double, val formant: Double)

    fun params(amount: Float): Params {
        val a = amount.coerceIn(-1f, 1f).toDouble()
        return Params(2.0.pow(a * 4.0 / 12.0), 1.0 + 0.10 * a)
    }

    fun process(x: FloatArray, amount: Float): FloatArray {
        if (kotlin.math.abs(amount) < 0.02f || x.size < 4096) return x
        val (p, f) = params(amount)
        val stretched = wsola(x, p)
        val shifted = resample(stretched, x.size)
        return envelopeWarp(shifted, f / p, x)
    }

    /** Time-stretch by [ratio] (>1 = longer) without changing pitch. */
    fun wsola(x: FloatArray, ratio: Double, n: Int = 1024, hs: Int = 256, tol: Int = 160): FloatArray {
        val outLen = (x.size * ratio).toInt()
        val out = FloatArray(outLen + n); val norm = FloatArray(outLen + n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val ha = hs / ratio
        var prevPos = 0
        var k = 0
        while (true) {
            val outPos = k * hs
            if (outPos + n > out.size) break
            val nominal = (k * ha).roundToInt()
            var best = nominal
            if (k > 0) {
                // pick the offset whose start best continues the previous frame's natural continuation
                val ref = prevPos + hs
                var bestC = Double.NEGATIVE_INFINITY
                val lo = max(0, nominal - tol); val hi = min(x.size - n, nominal + tol)
                var cand = lo
                while (cand <= hi) {
                    var c = 0.0
                    var i = 0
                    while (i < n / 2) { if (ref + i < x.size) c += x[ref + i] * x[cand + i]; i += 4 }
                    if (c > bestC) { bestC = c; best = cand }
                    cand += 2
                }
            }
            if (best + n > x.size) break
            for (i in 0 until n) { out[outPos + i] += x[best + i] * win[i]; norm[outPos + i] += win[i] }
            prevPos = best; k++
        }
        return FloatArray(outLen) { if (norm[it] > 1e-3f) out[it] / norm[it] else 0f }
    }

    fun resample(x: FloatArray, len: Int): FloatArray {
        val step = (x.size - 1).toDouble() / max(1, len - 1)
        return FloatArray(len) { i ->
            val t = i * step; val j = t.toInt(); val fr = (t - j).toFloat()
            if (j + 1 < x.size) x[j] * (1 - fr) + x[j + 1] * fr else x[min(j, x.size - 1)]
        }
    }

    /** Scale the spectral envelope of each frame by [warp] (>1 moves formants up). */
    fun envelopeWarp(x: FloatArray, warp: Double, reference: FloatArray? = null, n: Int = 1024, hop: Int = 256): FloatArray {
        val out = FloatArray(x.size + n); val norm = FloatArray(x.size + n)
        val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        val half = n / 2
        var pos = 0
        while (pos < x.size) {
            for (i in 0 until n) { re[i] = (if (pos + i < x.size) x[pos + i].toDouble() else 0.0) * win[i]; im[i] = 0.0 }
            fft(re, im, false)
            val env = envelope(re, im, cr, ci)
            for (k in 0..half) {
                val src = k / warp
                val j = src.toInt(); val fr = src - j
                val target = if (j + 1 <= half) env[j] * (1 - fr) + env[j + 1] * fr else env[half] * 0.1
                val g = (target / max(env[k], 1e-9)).coerceIn(0.05, 20.0)
                re[k] *= g; im[k] *= g
                if (k in 1 until half) { re[n - k] = re[k]; im[n - k] = -im[k] }
            }
            fft(re, im, true)
            for (i in 0 until n) { out[pos + i] += (re[i] * win[i]).toFloat(); norm[pos + i] += (win[i] * win[i]).toFloat() }
            pos += hop
        }
        val y = FloatArray(x.size) { if (norm[it] > 1e-3f) out[it] / norm[it] else 0f }
        // keep loudness equal to the original voice
        val ref = reference ?: x
        val g = sqrt(ref.sumOf { (it * it).toDouble() } / max(1e-9, y.sumOf { (it * it).toDouble() })).toFloat()
        for (i in y.indices) y[i] = (y[i] * g).coerceIn(-1f, 1f)
        return y
    }

    /** Cepstrally smoothed magnitude envelope (bins 0..n/2). */
    private fun envelope(re: DoubleArray, im: DoubleArray, cr: DoubleArray, ci: DoubleArray, lifter: Int = 40): DoubleArray {
        val n = re.size
        for (k in 0 until n) { cr[k] = ln(sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-9); ci[k] = 0.0 }
        fft(cr, ci, true)
        for (q in lifter until n - lifter) { cr[q] = 0.0; ci[q] = 0.0 }
        fft(cr, ci, false)
        return DoubleArray(n / 2 + 1) { exp(cr[it]) }
    }

    /** In-place radix-2 complex FFT; inverse is scaled by 1/n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
