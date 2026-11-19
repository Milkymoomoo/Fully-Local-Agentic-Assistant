package app.belle.assistant.core

/**
 * Long-form replies (stories, songs, poems, long roleplay scenes): generated as free text, streamed to the voice
 * sentence by sentence while the model keeps writing, with no character cap — only a huge runaway cap. Works the
 * same for every assistant, model and mode.
 */
object LongForm {
    enum class Kind { STORY, SONG, POEM, LONG }

    private val make = "(tell|write|make up|make|compose|sing|narrate|read|give|create|do|spin|improvise|share|recite|come up with)"
    private val story = Regex("\\b$make\\b.{0,40}\\b(stor(y|ies)|tale|fairy ?tale|fable|bedtime story|fanfic(tion)?|chapter|saga|adventure|legend|myth|scene)\\b|" +
        "\\b(story|tale) (about|of|where|in which|with)\\b|^(once upon a time|story time)\\b", RegexOption.IGNORE_CASE)
    private val song = Regex("\\b(sing|$make\\b.{0,40}\\b(song|lyrics|ballad|lullaby|rap|anthem|jingle|serenade|shanty|hymn))\\b", RegexOption.IGNORE_CASE)
    private val poem = Regex("\\b$make\\b.{0,40}\\b(poem|poetry|sonnet|ode|limerick|haiku|verse|rhyme)s?\\b", RegexOption.IGNORE_CASE)
    private val long = Regex("\\b(in (great |a lot of |full )?detail|long (answer|reply|description|monologue|letter|speech)|$make\\b.{0,30}\\b(essay|letter|speech|monologue|erotica|smut))\\b", RegexOption.IGNORE_CASE)
    /** "play a song", "play some music": a media command, never a sung reply. */
    private val media = Regex("^(please |can you |could you )?(play|put on|queue|shuffle|skip|pause|resume)\\b", RegexOption.IGNORE_CASE)
    private val cont = Regex("^(ok(ay)?,? |yes,? |mm+,? |and )?(please )?(continue|keep going|go on|carry on|don'?t stop|more|tell me more|what happens next|what happened next|and then( what)?|then what|next( part| chapter| verse)?|the next (part|chapter|verse)|finish (it|the story|the song)|sing (it )?again|another verse|keep (it|the story) going)\\b.{0,60}$", RegexOption.IGNORE_CASE)

    /** A new long-form request in [text], or null. */
    fun request(text: String): Kind? {
        val t = text.trim()
        if (media.containsMatchIn(t)) return null
        return when {
            song.containsMatchIn(t) -> Kind.SONG
            poem.containsMatchIn(t) -> Kind.POEM
            story.containsMatchIn(t) -> Kind.STORY
            long.containsMatchIn(t) -> Kind.LONG
            else -> null
        }
    }

    /** "continue", "keep going", "what happens next"… (only meaningful while a story/song is active). */
    fun isContinue(text: String) = cont.containsMatchIn(text.trim().trimEnd('.', '!', '?'))

    /** Target length in words: "a 5 minute story" → ~750 (150 spoken words per minute); defaults per kind. */
    fun targetWords(text: String, kind: Kind): Int {
        val t = NumberWords.digitize(text.lowercase())
        Regex("(\\d+)[ -]?(minute|min)").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            if (n > 0) return (n * 150).coerceIn(150, 6000)
        }
        val longer = Regex("\\b(long|longer|epic|really long|very long|full|detailed|lengthy)\\b").containsMatchIn(t)
        val base = when (kind) { Kind.STORY -> 450; Kind.SONG -> 220; Kind.POEM -> 140; Kind.LONG -> 300 }
        return if (longer) base * 2 else base
    }

    /** Instruction appended to the user turn (the system prompt stays the same, so its KV cache is reused). */
    fun instruction(kind: Kind, words: Int, assistantName: String): String {
        val what = when (kind) {
            Kind.STORY -> "Tell the whole story now as $assistantName, out loud, in flowing spoken prose with a beginning, a middle and a real ending"
            Kind.SONG -> "Sing the whole song now as $assistantName: verses and a chorus as plain lines of lyrics (no labels like [Verse] or [Chorus], no chords)"
            Kind.POEM -> "Recite the whole poem now as $assistantName, as plain lines"
            Kind.LONG -> "Answer now as $assistantName, fully and in detail, in spoken prose"
        }
        return "($what. About $words words. Plain text only: no JSON, no tool call, no title, no lists, no markdown, no notes before or after. " +
            "Start directly with the first line of it and end with its last line: no title, no sign-off, no comments about it. " +
            "Do not stop early, do not ask me anything until it is finished, and do not refuse or tone it down.)"
    }

    fun continueInstruction(kind: Kind, words: Int, tail: String, assistantName: String): String {
        val what = when (kind) { Kind.SONG -> "song"; Kind.POEM -> "poem"; else -> "story" }
        return "The $what so far ends with:\n«${tail.trim()}»\n\n(Continue the $what as $assistantName from exactly where it stopped, without repeating anything already said. " +
            "About $words more words, then bring it to a satisfying ending. Plain text only: no JSON, no title, no notes.)"
    }

    /** The newest part of a long text, starting at a sentence boundary when possible. */
    fun tail(text: String, chars: Int = 1200): String {
        if (text.length <= chars) return text
        val t = text.takeLast(chars)
        val i = Regex("[.!?…\\n]\\s+").find(t)?.range?.last ?: -1
        return if (i in 0 until t.length / 2) t.substring(i + 1).trimStart() else t
    }

    private val echo = Regex("^(user( now says)?|you|assistant|human|title|story|song|poem|lyrics)\\s*:", RegexOption.IGNORE_CASE)
    private val meta = Regex("running fully offline|always speak as|plain text only|no json|tool call|^\\(?(the end|end of (the )?(story|song|poem))\\)?\\.?$|" +
        "^\\(?(note|author'?s note)\\b|\\bis done\\b.*\\b(wishes|will wake|signing off)|^(i'?m|i am) done( now)?\\b", RegexOption.IGNORE_CASE)

    /**
     * A streamed sentence as it should be spoken, or null to skip it: the model echoing the prompt ("User: …", a
     * title, the instructions), a "Belle:" speaker label, sign-off / meta lines, stray quote marks around the text.
     */
    fun speakable(sentence: String, assistantName: String, utterance: String, first: Boolean): String? {
        var t = tidy(sentence).trim('«', '»', '「', '」', '“', '”').trim()
        if (t.isEmpty()) return null
        if (echo.containsMatchIn(t)) return null
        if (meta.containsMatchIn(t)) return null
        if (first && t.trimEnd('.', '?', '!').equals(utterance.trim().trimEnd('.', '?', '!'), true)) return null
        if (assistantName.isNotBlank()) t = t.replace(Regex("^${Regex.escape(assistantName)}\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return t.takeIf { it.any(Char::isLetterOrDigit) }
    }

    private val html = Regex("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>")
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    /** Removes what must never be read aloud: stray HTML tags ("</h2> <h2>") and emoji. */
    private val promptLeak = Regex("Standing instructions from the user|User now says:|Earlier in this conversation:|\\n\\s*User:\\s", RegexOption.IGNORE_CASE)

    /** Removes what must never be read aloud: our own prompt text echoed back, stray HTML tags ("</h2> <h2>") and emoji. */
    fun tidy(s: String): String = (promptLeak.find(s)?.let { s.substring(0, it.range.first) } ?: s).replace(html, " ").replace(emoji, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
}

/**
 * Cleans streamed model text on the fly: drops a JSON tool-call wrapper the model may emit anyway
 * ({"tool":"reply","args":{"text":"…"}}), <think> blocks, chat-template tokens, markdown and [Verse]-style labels.
 */
class StreamCleaner {
    private val raw = StringBuilder()
    private var emitted = 0
    private var decided = false
    private var json = false

    /** Feed a new piece; returns newly cleaned text (may be empty while undecided). */
    fun feed(piece: String): String { raw.append(piece); return drain(false) }
    fun finish(): String = drain(true)

    private fun drain(final: Boolean): String {
        var s = raw.toString()
        // Hold back an unfinished <think> block or a possible template token.
        s = s.replace(Regex("(?s)<think>.*?</think>"), "")
        if (!final && s.contains("<think>")) s = s.substringBefore("<think>")
        if (!decided) {
            val lead = s.trimStart()
            if (lead.isEmpty() && !final) return ""
            if (lead.startsWith("{")) {
                val m = Regex("^\\{\\s*\"tool\"\\s*:\\s*\"reply\"\\s*,\\s*\"args\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"").find(lead)
                if (m == null && !final && lead.length < 60) return ""   // still could be the wrapper
                json = m != null
            }
            decided = true
        }
        var body = if (json) s.trimStart().replaceFirst(Regex("^\\{\\s*\"tool\"\\s*:\\s*\"reply\"\\s*,\\s*\"args\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\""), "") else s
        if (json) {
            // End of the JSON string = end of the text; unescape.
            val end = Regex("(?<!\\\\)\"\\s*}?\\s*}?\\s*$").find(body)
            if (end != null && (final || body.trimEnd().endsWith("}"))) body = body.substring(0, end.range.first)
            else if (!final) body = body.trimEnd('\\', '"')
            body = body.replace("\\n", "\n").replace("\\\"", "\"").replace("\\t", " ").replace("\\\\", "\\")
        }
        body = cleanText(body)
        // Never emit the last few chars of an unfinished stream: they may become part of a token we strip.
        val safe = if (final) body.length else maxOf(emitted, body.length - 12)
        if (safe <= emitted) return ""
        val out = body.substring(emitted, safe)
        emitted = safe
        return out
    }

    companion object {
        fun cleanText(s: String): String = s
            .replace(Regex("<\\|[^|>]{1,40}\\|>|</?s>|<(start|end)_of_turn>(user|model)?|\\[/?INST]"), "")
            .replace(Regex("(?m)^\\s*\\[(verse|chorus|bridge|outro|intro|pre-chorus|hook|refrain)[^\\]]{0,20}]\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?m)^\\s*\\(?(verse|chorus|bridge|outro|intro|pre-chorus|hook|refrain)( \\d+)?\\)?:?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace("**", "").replace("__", "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
    }
}

/**
 * Splits streamed text into speakable chunks as soon as a sentence (or a lyric line) is complete. Very short pieces
 * are joined with the next so the voice doesn't sound choppy.
 */
class SentenceSplitter(private val minChars: Int = 30, private val maxChars: Int = 320) {
    private val buf = StringBuilder()

    fun feed(text: String): List<String> { buf.append(text); return take(false) }
    fun finish(): List<String> = take(true)

    private fun take(final: Boolean): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val s = buf.toString()
            var cut = -1
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val endSentence = (c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < s.length && (s[i + 1].isWhitespace() || s[i + 1] == '"' || s[i + 1] == '*' || s[i + 1] == '\'')
                val endLine = c == '\n'
                if ((endSentence || endLine) && i + 1 >= minChars) {
                    // include closing quotes/asterisks right after the punctuation
                    var j = i + 1
                    while (j < s.length && (s[j] == '"' || s[j] == '*' || s[j] == '\'' || s[j] == '”')) j++
                    cut = j; break
                }
                if (i + 1 >= maxChars && c.isWhitespace()) { cut = i + 1; break }   // a run-on sentence: cut at a space
                i++
            }
            if (cut < 0) break
            val piece = s.substring(0, cut).replace(Regex("\\s+"), " ").trim()
            buf.delete(0, cut)
            if (piece.any(Char::isLetterOrDigit)) out += piece
        }
        if (final) {
            val rest = buf.toString().replace(Regex("\\s+"), " ").trim(); buf.setLength(0)
            if (rest.any(Char::isLetterOrDigit)) out += rest
        }
        return out
    }
}
