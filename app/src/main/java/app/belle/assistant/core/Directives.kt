package app.belle.assistant.core

/**
 * Pinned active directives: standing instructions the user gives in conversation ("from now on call me puppy",
 * "always answer in rhymes", "keep the story set in Paris", "stop swearing"). They are kept in the user's own words,
 * pinned at the top of every model turn (never dropped by the memory budget or summarized away) until the user
 * changes or cancels them. Generic for every assistant, model and mode.
 */
object Directives {
    const val MAX = 10
    const val MAX_CHARS = 220

    sealed class Change {
        data class Add(val text: String, val replaces: Regex? = null) : Change()
        data class Remove(val match: Regex) : Change()
        object ClearAll : Change()
    }

    private val I = RegexOption.IGNORE_CASE
    private val clearAll = Regex("^(please )?(forget|drop|cancel|clear|ignore) (all|every) (of )?(my |the |your )?(instructions|rules|directives|orders)|^(go|switch) back to (normal|default|your (normal|usual) self)|^reset (your|the) (instructions|rules)", I)
    private val stopCalling = Regex("^(please )?(stop|quit|don'?t keep) calling me (.+)", I)
    private val forgetRule = Regex("^(please )?(you can )?(forget|drop|cancel|ignore|stop following|never ?mind) (that|the|my) (last )?(instruction|rule|request|thing about|part about)\\s*(.*)", I)
    private val callMe = Regex("^(?:(?:from now on|okay|ok|and|please)[, ]+)*(?:call me|my name is|i'?m called|refer to me as|address me as) (.+)", I)
    private val standing = Regex("^(?:(?:okay|ok|and|also|please|hey)[, ]+)*(from now on|for the rest of (the|this|our) (conversation|chat|night|story)|until i say (otherwise|stop)|whenever (i|we) talk|always|never|don'?t ever|do not ever|keep|stay|remember to|remember that you|you are now|you'?re now|be my|pretend (to be|you are|you'?re)|act like|act as|talk like|speak like|speak in|talk in|answer in|reply in|respond in|only (answer|reply|speak|talk)|stop (\\w+ing)|no more|let'?s (roleplay|role play|pretend)|in this story)\\b(.*)", I)
    /** Not instructions: questions and everyday phrases that happen to start like one. */
    private val notDirective = Regex("\\?$|^(always|never) (mind|again)\\b|^keep (going|it up)$|^stop( it| that| now)?$|^stop (listening|talking)$", I)

    /** Every change in [utterance], clause by clause ("stop calling me puppy, call me captain instead"). */
    fun parseAll(utterance: String): List<Change> {
        val whole = parse(utterance)
        val clauses = utterance.split(Regex("[,;.!]+\\s*|\\s+(?:and|but|then)\\s+(?=(?:call|stop|from now|always|never|don'?t|do not|forget|keep|remember|go back)\\b)", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size <= 1) return listOfNotNull(whole)
        val parts = clauses.mapNotNull { parse(it) }
        return if (parts.isEmpty()) listOfNotNull(whole) else parts
    }

    fun parse(utterance: String): Change? {
        val t = utterance.trim().trimEnd('.', '!').replace('’', '\'')
        if (t.length < 6) return null
        if (clearAll.containsMatchIn(t)) return Change.ClearAll
        stopCalling.find(t)?.let { m ->
            val what = m.groupValues[3].trim().replace(Regex("\\s+(anymore|any more|please|now)$", I), "").trim()
            return Change.Add("Do not call me \"$what\" anymore.", Regex("\\bcall (me|the user) .*${Regex.escape(what.take(30))}", I))
        }
        forgetRule.find(t)?.let { m ->
            val about = m.groupValues[7].trim()
            return Change.Remove(if (about.isEmpty()) Regex(".*") else Regex(Bm25.terms(about).joinToString("|") { Regex.escape(it) }.ifEmpty { ".*" }, I))
        }
        if (notDirective.containsMatchIn(t)) return null
        callMe.find(t)?.let { m ->
            val name = m.groupValues[1].trim().replace(Regex("\\s+(instead|please|from now on|now|again|okay|ok)$", I), "").trim().take(60)
            if (name.isEmpty() || Regex("^(back|later|tomorrow|at |on |in \\d|when)", I).containsMatchIn(name)) return null
            return Change.Add("Call me $name.", Regex("^(call me|do not call me)\\b", I))
        }
        if (standing.containsMatchIn(t) && t.split(Regex("\\s+")).size >= 3) return Change.Add(t.take(MAX_CHARS).let { it[0].uppercase() + it.substring(1) } + ".")
        return null
    }

    /** Applies [c] to [current] (oldest first), keeping at most [MAX]. */
    fun apply(current: List<String>, c: Change): List<String> = when (c) {
        is Change.ClearAll -> emptyList()
        is Change.Remove -> current.dropLast(0).let { l -> val hit = l.lastOrNull { c.match.containsMatchIn(it) }; if (hit == null) l else l - hit }
        is Change.Add -> {
            val kept = current.filterNot { old -> old.equals(c.text, true) || (c.replaces?.containsMatchIn(old) == true) }
            (kept + c.text).takeLast(MAX)
        }
    }

    /** The pinned block for the model, or "" when there are none. */
    fun render(list: List<String>): String =
        if (list.isEmpty()) "" else "Standing instructions from the user (keep following them every reply until the user changes them):\n" +
            list.joinToString("\n") { d ->
                // "me" in the user's words is the user: spell it out for small models
                Regex("^Call me (.+?)\\.?$").find(d)?.let { "- Call the user \"${it.groupValues[1]}\" (that is what they want to be called)." }
                    ?: Regex("^Do not call me \"(.+)\" anymore\\.$").find(d)?.let { "- Do not call the user \"${it.groupValues[1]}\" anymore." }
                    ?: "- $d" }
}
