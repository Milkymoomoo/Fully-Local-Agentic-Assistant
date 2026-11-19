package app.belle.assistant.core

/**
 * Turns any typed assistant name into keyword-spotter input, so the wake word
 * can be changed without training a model. The KWS model is a BPE/unigram
 * transducer; keywords must be given as its sentencepiece tokens, which we
 * compute here with a Viterbi segmentation over bpe.vocab (piece \t logprob).
 */
class UnigramTokenizer(vocabLines: List<String>) {
    private val scores = HashMap<String, Float>()
    private val maxLen: Int

    init {
        var ml = 1
        for (line in vocabLines) {
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val piece = parts[0]
            if (piece.startsWith("<") && piece.endsWith(">")) continue
            scores[piece] = parts[1].toFloatOrNull() ?: continue
            ml = maxOf(ml, piece.length)
        }
        maxLen = ml
    }

    fun has(piece: String) = scores.containsKey(piece)

    /** Returns null if some character cannot be covered by the vocabulary. */
    fun encode(text: String): List<String>? {
        val out = ArrayList<String>()
        for (word in text.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            out += encodeWord("\u2581$word") ?: return null
        }
        return out
    }

    private fun encodeWord(w: String): List<String>? {
        val n = w.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (end in 1..n) {
            for (start in maxOf(0, end - maxLen) until end) {
                if (best[start] == Float.NEGATIVE_INFINITY) continue
                val s = scores[w.substring(start, end)] ?: continue
                val cand = best[start] + s
                if (cand > best[end]) { best[end] = cand; back[end] = start }
            }
        }
        if (back[n] < 0) {
            // A lone "▁" piece may exist; try "▁" + rest as a fallback.
            return null
        }
        val pieces = ArrayList<String>()
        var e = n
        while (e > 0) { val s = back[e]; pieces += w.substring(s, e); e = s }
        return pieces.reversed()
    }
}

object WakeWord {
    /** Clean a user-typed phrase to what the English KWS model can spell: A-Z, apostrophe, spaces. */
    fun clean(name: String): String = name.uppercase()
        .replace(Regex("[^A-Z' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Starting wake phrases for an assistant name. The user can add/remove any number later. */
    fun defaultPhrases(name: String): List<String> {
        val n = callName(name)
        val full = name.trim().lowercase().ifEmpty { n }
        return (listOf("hey $n", "okay $n") + if (full != n) listOf("hey $full") else emptyList()).distinct()
    }

    /** What you'd actually say: "Bob From Accounting" → "bob"; single names stay as they are. */
    fun callName(name: String): String {
        val w = clean(name).lowercase().split(" ").filter { it.isNotEmpty() }
        return when { w.isEmpty() -> "bob"; w.size >= 3 -> w.first(); else -> w.joinToString(" ") }
    }

    /** "OK" and "OKAY" sound the same but tokenize differently, so accept both spellings. */
    private fun variants(p: String): List<String> {
        val out = linkedSetOf(p)
        val w = p.split(" ")
        if (w.first() == "OKAY") out += (listOf("OK") + w.drop(1)).joinToString(" ")
        if (w.first() == "OK") out += (listOf("OKAY") + w.drop(1)).joinToString(" ")
        return out.toList()
    }

    /**
     * Lines for the keyword-spotter keywords file: tokens, then :boost, #threshold, @label.
     * Every phrase is active at once; there is no limit on how many. Higher sensitivity
     * lowers the detection threshold (wakes more easily, more false wakes).
     * Phrases that can't be spelled are skipped and returned in [rejected].
     */
    fun keywordLines(tok: UnigramTokenizer, phrases: List<String>, sensitivity: Float, rejected: MutableList<String>? = null): List<String> {
        val threshold = (0.35f - 0.2f * sensitivity.coerceIn(0f, 1f)).coerceIn(0.05f, 0.5f)
        val boost = 1.0f + sensitivity.coerceIn(0f, 1f)
        val lines = LinkedHashMap<String, String>()
        for (raw in phrases) {
            val p = clean(raw)
            if (p.isEmpty()) { rejected?.add(raw); continue }
            val label = clean(raw).replace(' ', '_')
            var any = false
            for (v in variants(p)) {
                val toks = tok.encode(v) ?: continue
                any = true
                lines.getOrPut(v) {
                    "${toks.joinToString(" ")} :${"%.1f".format(java.util.Locale.US, boost)} #${"%.2f".format(java.util.Locale.US, threshold)} @$label"
                }
            }
            if (!any) rejected?.add(raw)
        }
        return lines.values.toList()
    }
}
