package app.belle.assistant.speech

/**
 * Pure helpers for speech recognition input (unit-tested on the JVM).
 *
 * The on-phone recognizer (Moonshine tiny) fails on audio longer than ~9.3 s: ONNX Runtime throws inside the
 * decoder ("Attempting to broadcast an axis by a dimension other than 1") and sherpa-onnx returns an EMPTY result.
 * So a long spoken request ("tell me a story about … named Timmy who has …") came back as nothing at all, and the
 * assistant kept listening. Every segment is therefore cut into pieces of at most [MAX_DECODE_S] seconds, at the
 * quietest moment (a breath between words), before it reaches the recognizer.
 */
object SpeechChunks {
    const val MAX_DECODE_S = 8.0f
    /** A single utterance is finalized after this much continuous speech, even if the speaker never pauses. */
    const val MAX_UTTERANCE_S = 30f

    /** Ranges of [x] to decode separately: each at most [maxS] seconds, cut at the quietest 20 ms between [minS] and [maxS]. */
    fun split(x: FloatArray, rate: Int, maxS: Float = MAX_DECODE_S, minS: Float = maxS * 0.55f): List<IntRange> {
        val max = (maxS * rate).toInt(); val min = (minS * rate).toInt().coerceAtMost(max - 1)
        if (x.size <= max) return listOf(x.indices)
        val win = maxOf(1, rate / 50)
        val out = ArrayList<IntRange>(); var start = 0
        while (x.size - start > max) {
            var best = start + max; var bestE = Double.MAX_VALUE
            var p = start + min
            while (p + win <= start + max) {
                var e = 0.0; for (i in p until p + win) e += x[i] * x[i]
                if (e < bestE) { bestE = e; best = p + win / 2 }
                p += win / 2
            }
            out += start until best; start = best
        }
        out += start until x.size
        return out
    }
}
