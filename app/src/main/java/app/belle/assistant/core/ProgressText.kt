package app.belle.assistant.core

/**
 * Splits a long-job progress label into what the progress block shows on separate lines:
 * "Detailed shape 7/12" → stage "Detailed shape", step 7 of 12; "Decoding" → stage only. Pure Kotlin (unit-tested).
 */
object ProgressText {
    data class Parts(val stage: String, val step: Int? = null, val steps: Int? = null) {
        val stepLine: String? get() = stepLine("Step")
        fun stepLine(word: String): String? = if (step != null && steps != null) "$word $step of $steps" else null
    }
    private val STEP = Regex("""^(.*?)\s*\(?\b(\d+)\s*/\s*(\d+)\)?(.*)$""")

    fun split(label: String?): Parts {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return Parts("Starting…")
        val m = STEP.matchEntire(l) ?: return Parts(l)
        val stage = (m.groupValues[1] + m.groupValues[4]).replace(Regex("\\s+"), " ").trim().trimEnd('·', '-', ':').trim()
        val i = m.groupValues[2].toInt(); val n = m.groupValues[3].toInt()
        if (n <= 0 || i > n) return Parts(l)
        return Parts(stage.ifEmpty { "Working" }, i, n)
    }

    /** "42 %": rounded down so it never says 100 % before it is done. */
    fun percent(f: Float): String = "${(f.coerceIn(0f, 1f) * 100).toInt().coerceAtMost(if (f >= 1f) 100 else 99)} %"
}
