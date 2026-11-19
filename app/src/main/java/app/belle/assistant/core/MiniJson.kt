package app.belle.assistant.core

/** Tiny JSON reader (no Android deps, so it's unit-testable). Objects → Map, arrays → List, numbers → Double. */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); v }
    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    if (st == i) error("bad json at $i: '$c'"); s.substring(st, i).toDouble() }
            }
        }
        fun str(): String {
            i++; val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; when (val e = s[i]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(e) } } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
    }
}
