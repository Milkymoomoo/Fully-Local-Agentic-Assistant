package app.belle.assistant.core

/** Parses spoken numbers, durations and clock times ("five thirty pm", "an hour and a half"). */
object NumberWords {
    private val units = mapOf(
        "zero" to 0, "oh" to 0, "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "couple" to 2, "few" to 3,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /** Replace number words with digits: "twenty five" -> "25", "a hundred" -> "100". */
    fun digitize(text: String): String {
        val words = text.lowercase().replace("-", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val (value, used) = readNumber(words, i)
            if (used > 0) { out += value.toString(); i += used } else { out += words[i]; i++ }
        }
        return out.joinToString(" ")
    }

    private fun isNumWord(w: String) = w in units || w in tens || w == "hundred" || w == "thousand"

    private fun readNumber(w: List<String>, start: Int): Pair<Int, Int> {
        var i = start
        var total = 0
        var current = 0
        var any = false
        while (i < w.size) {
            val word = w[i]
            // "a"/"an" only count as 1 before a unit word ("a minute", "an hour", "a hundred")
            if ((word == "a" || word == "an") && !any) {
                val next = w.getOrNull(i + 1) ?: break
                if (next == "hundred" || next == "thousand" || next in DURATION_UNITS || next == "couple" || next == "few" || next == "half") {
                    if (next == "couple" || next == "few") { i++; continue }
                    if (next == "half") break
                    current = 1; any = true; i++; continue
                }
                break
            }
            when {
                word == "and" && any && w.getOrNull(i + 1)?.let { it in units || it in tens } == true &&
                    w.getOrNull(i + 1) != "a" -> { i++ }
                word in tens -> {
                    if (current % 100 != 0) break          // "seven thirty" is 7 then 30, not 37
                    current += tens.getValue(word); any = true; i++
                }
                word in units && word != "a" && word != "an" -> {
                    if (word == "oh" && !any) break
                    val r = current % 100
                    if (any && !(r == 0 || (r >= 20 && r % 10 == 0)) ) break   // "nine five" stays two numbers
                    if (any && r >= 20 && units.getValue(word) >= 10) break
                    current += units.getValue(word); any = true; i++
                }
                word == "hundred" && any -> { current *= 100; i++ }
                word == "thousand" && any -> { total += current * 1000; current = 0; i++ }
                word.matches(Regex("\\d+")) && !any -> return word.toInt() to 1
                else -> break
            }
        }
        return if (any) (total + current) to (i - start) else 0 to 0
    }

    val DURATION_UNITS = setOf("second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs")

    /** "5 minutes", "an hour and a half", "1 hour 30 minutes", "90 seconds", "half an hour" -> seconds. */
    fun parseDurationSeconds(text: String): Int? {
        var t = text.lowercase().replace(Regex("\\bhalf (an|a) hour\\b"), "30 minutes")
            .replace(Regex("\\bhalf (a|an) minute\\b"), "30 seconds")
            .replace(Regex("\\bquarter (of )?(an|a) hour\\b"), "15 minutes")
        t = digitize(t)
        var total = 0.0
        var found = false
        val re = Regex("(\\d+(?:\\.\\d+)?)\\s*(and a half\\s+)?(seconds?|secs?|minutes?|mins?|hours?|hrs?)(\\s+and a half)?")
        for (m in re.findAll(t)) {
            var n = m.groupValues[1].toDouble()
            if (m.groupValues[2].isNotBlank() || m.groupValues[4].isNotBlank()) n += 0.5
            val unit = m.groupValues[3]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            found = true
        }
        return if (found && total > 0) total.toInt() else null
    }

    data class ClockTime(val hour: Int, val minute: Int)

    /**
     * "7", "7:30", "7 30 pm", "seven thirty in the morning", "noon", "midnight", "quarter past 6",
     * "half past 7 pm", "6 am". Without am/pm, 1-6 o'clock is treated as pm unless [preferAm].
     */
    fun parseClock(text: String, preferAm: Boolean = false): ClockTime? {
        var t = digitize(text).replace(".", "").replace("a m", "am").replace("p m", "pm")
            .replace(Regex("\\b(\\d{1,2}) oh (\\d)\\b"), "$1:0$2")
        if (Regex("\\bnoon\\b").containsMatchIn(t)) return ClockTime(12, 0)
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return ClockTime(0, 0)
        val pm = Regex("\\b(pm|evening|tonight|afternoon|at night)\\b").containsMatchIn(t)
        val am = Regex("\\b(am|morning)\\b").containsMatchIn(t)
        var hour: Int
        var minute = 0
        val past = Regex("\\b(quarter|half|\\d+)\\s+(past|after)\\s+(\\d{1,2})\\b").find(t)
        val to = Regex("\\b(quarter|\\d+)\\s+(to|till|before)\\s+(\\d{1,2})\\b").find(t)
        val hm = Regex("\\b(\\d{1,2})(?::|\\s)(\\d{2})\\b").find(t)
        val compact = Regex("\\b(\\d{3,4})\\s*(am|pm)\\b").find(t)
        val h = Regex("\\b(\\d{1,2})\\s*(o'?clock|am|pm)?\\b").find(t)
        when {
            past != null -> {
                hour = past.groupValues[3].toInt()
                minute = when (past.groupValues[1]) { "quarter" -> 15; "half" -> 30; else -> past.groupValues[1].toInt() }
            }
            to != null -> {
                hour = (to.groupValues[3].toInt() + 11) % 12
                if (hour == 0) hour = 12
                minute = 60 - (if (to.groupValues[1] == "quarter") 15 else to.groupValues[1].toInt())
            }
            compact != null -> {
                val v = compact.groupValues[1].toInt(); hour = v / 100; minute = v % 100
            }
            hm != null -> { hour = hm.groupValues[1].toInt(); minute = hm.groupValues[2].toInt() }
            h != null -> hour = h.groupValues[1].toInt()
            else -> return null
        }
        if (hour > 23 || minute > 59) return null
        if (hour <= 12) {
            if (pm && hour < 12) hour += 12
            else if (am && hour == 12) hour = 0
            else if (!am && !pm && !preferAm && hour in 1..6) hour += 12
        }
        return ClockTime(hour, minute)
    }
}
