package app.belle.assistant.book

/** Spoken "Read to me" commands. [active] = a book is playing or paused, so "stop", "pause", "resume", "faster"… mean the book. */
sealed class BookCmd {
    data class Read(val title: String?) : BookCmd()        // null = the last book
    object Pause : BookCmd(); object Stop : BookCmd(); object Resume : BookCmd()
    object NextChapter : BookCmd(); object PrevChapter : BookCmd(); object RestartChapter : BookCmd()
    data class Back(val sentences: Int) : BookCmd()
    object Faster : BookCmd(); object Slower : BookCmd()
    data class GoTo(val chapter: Int) : BookCmd()
    data class Sleep(val minutes: Int) : BookCmd()          // 0 = cancel the timer
    object Recap : BookCmd(); object WhereAmI : BookCmd()
    /** Unload the book (bookmark and file kept); [everything] = also the voice, speech-to-text and the model. */
    data class Unload(val everything: Boolean) : BookCmd()
}

object BookCommands {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(hey |ok |okay )?(please )?(can you |could you |would you |will you )?"), "").replace(Regex(" please$"), "").trim()

    private val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty")
    fun number(s: String): Int? {
        s.trim().toIntOrNull()?.let { return it }
        val w = s.trim().split(' ', '-').filter { it.isNotEmpty() }
        var total = 0
        for (x in w) { val i = numbers.indexOf(x); if (i < 0) return null; total += if (i <= 20) i else (i - 18) * 10 }
        return if (w.isEmpty()) null else total
    }

    fun parse(text: String, active: Boolean): BookCmd? {
        val t = norm(text); if (t.isEmpty()) return null
        Regex("^(?:read|play|start|open)(?: me| to me)?(?: the book| my book| book| the audiobook| audiobook)?(?: called| named)? (.+)$").find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what in setOf("a book", "my book", "the book", "something", "a story from my books", "my audiobook")) return BookCmd.Read(null)
            if (Regex("^(a story|me a story|a poem|a song|the news|.*news.*|my messages|.*notification.*|.*message.*|.*email.*|.*text.*)$").matches(what)) return null
            return BookCmd.Read(what)
        }
        if (Regex("^(read to me|read me something|continue (my )?(book|reading|audiobook)|resume (my )?(book|reading|audiobook)|keep reading( my book)?|read my book|start reading|audiobook mode)$").matches(t))
            return if (active && t.startsWith("keep")) BookCmd.Resume else BookCmd.Read(null)
        Regex("^(?:stop|end|pause) (?:reading|the book)? ?(?:in|after) (.+?) (?:minutes?|mins?)$").find(t)?.let { m -> number(m.groupValues[1])?.let { return BookCmd.Sleep(it) } }
        Regex("^(?:set )?(?:a )?sleep timer (?:for )?(.+?) (?:minutes?|mins?)$").find(t)?.let { m -> number(m.groupValues[1])?.let { return BookCmd.Sleep(it) } }
        if (Regex("^(cancel|turn off|stop) (the )?sleep timer$").matches(t)) return BookCmd.Sleep(0)
        // "stop reading" stops (bookmark saved, playback and its notification end); "pause reading" pauses
        if (Regex("^(stop|end) (reading|the book|the audiobook|the story)$").matches(t)) return BookCmd.Stop
        if (Regex("^pause (reading|the book|the audiobook)$").matches(t)) return BookCmd.Pause
        if (Regex("^(unload|close|eject) (the |my )?(book|audiobook)$").matches(t)) return BookCmd.Unload(false)
        if (Regex("^(unload|free) (everything|all( the)? models|all|the models|memory)( you have loaded)?$").matches(t)) return BookCmd.Unload(true)
        Regex("^(?:go to |skip to |jump to |read )?chapter (.+)$").find(t)?.let { m -> number(m.groupValues[1])?.let { return BookCmd.GoTo(it) } }
        if (Regex("^what (just )?happened( so far| there| in the (book|story|chapter))?$|^(give me a )?recap( please)?$|^summari[sz]e (that|what just happened|the last bit)$|^what did i miss$").matches(t)) return if (active) BookCmd.Recap else null
        if (!active) return null
        return when {
            Regex("^(pause|stop|hold on|wait|be quiet|quiet|shush|hush|stop it|enough)$").matches(t) -> if (t == "stop" || t == "enough") BookCmd.Stop else BookCmd.Pause
            Regex("^(resume|continue|go on|keep going|play|unpause|carry on|go ahead)$").matches(t) -> BookCmd.Resume
            Regex("^(next chapter|skip (this )?chapter|skip ahead( a chapter)?|go to the next chapter)$").matches(t) -> BookCmd.NextChapter
            Regex("^(previous chapter|last chapter|go back a chapter|go to the previous chapter|back a chapter)$").matches(t) -> BookCmd.PrevChapter
            Regex("^(start (the|this) chapter (again|over)|restart (the|this) chapter|from the (start|beginning) of the chapter)$").matches(t) -> BookCmd.RestartChapter
            Regex("^(go back|back up|rewind|repeat that|say that again|what did (she|he|they|you) say|again)$").matches(t) -> BookCmd.Back(3)
            Regex("^go back (a lot|more|further)$").matches(t) -> BookCmd.Back(12)
            Regex("^(faster|read faster|speed up|a bit faster|go faster|quicker)$").matches(t) -> BookCmd.Faster
            Regex("^(slower|read slower|slow down|a bit slower|go slower)$").matches(t) -> BookCmd.Slower
            Regex("^(where am i|what chapter (is this|am i on)|which chapter)$").matches(t) -> BookCmd.WhereAmI
            else -> null
        }
    }

    /** Best library title for a spoken one, by word overlap (ignoring little words); null if nothing fits. */
    fun bestTitle(spoken: String, titles: List<String>): String? {
        val stop = setOf("the", "a", "an", "of", "and", "book", "to", "me", "my", "in", "on")
        fun words(s: String) = norm(s).split(' ').filter { it.length > 1 && it !in stop }.toSet()
        val w = words(spoken); if (w.isEmpty()) return null
        return titles.map { t -> val tw = words(t); t to (w.count { x -> tw.any { y -> y == x || (x.length >= 4 && y.startsWith(x.take(4))) } }.toDouble() / w.size) }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }
}
