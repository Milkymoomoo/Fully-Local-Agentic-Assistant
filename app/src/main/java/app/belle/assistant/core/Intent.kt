package app.belle.assistant.core

/**
 * Sanity check for phone actions chosen by the language model. Small models often turn chat, flirting or roleplay
 * into a tool call ("I want you to f*** me" -> call "me", "take my clothes off" -> open_app): a phone action is only
 * accepted when the utterance actually contains words for that action. Otherwise the model is asked again for a
 * spoken reply only. Informational tools (web, news) and replies are always accepted.
 */
object Intent {
    private val words: Map<String, Regex> = mapOf(
        "call" to "\\b(call|phone|dial|ring|facetime)\\b",
        "sms" to "\\b(text|texts|message|sms|send|tell|let)\\b",
        "open_app" to "\\b(open|launch|start|run|show|go to|switch to|bring up)\\b",
        "set_alarm" to "\\b(alarm|wake me|wake up)\\b",
        "set_timer" to "\\b(timer|countdown|count down)\\b",
        "flashlight" to "\\b(flash ?light|torch|light|dark)\\b",
        "volume" to "\\b(volume|louder|quieter|softer|mute|unmute|sound|loud|quiet)\\b",
        "open_settings" to "\\b(settings?|wi-?fi|bluetooth|internet|data|nfc|location|gps|battery|display|brightness|sound|airplane|apps?|accessibility)\\b",
        "media" to "\\b(play|pause|resume|music|song|track|skip|next|previous|stop|playback|video|podcast)\\b",
        "navigate" to "\\b(directions?|navigate|navigation|map|maps|route|drive|walk|take me to|get to|way to|where is|nearest|near me)\\b",
        "reminder" to "\\b(remind|reminder)\\b",
        "calendar_event" to "\\b(calendar|event|schedule|meeting|appointment)\\b",
        "note" to "\\b(note|notes|write (that |this |it )?down|jot|remember that|save)\\b",
        "read_notes" to "\\b(notes?)\\b",
        "device_info" to "\\b(time|date|day|today|battery|charge|storage|space|network|wi-?fi|signal|connected|clock)\\b",
        "screen" to "\\b(tap|click|press|type|scroll|swipe|back|home|recents?|notifications?|quick settings|screenshot|lock|screen|button)\\b",
        "camera" to "\\b(camera|photo|picture|pic|selfie|video|record)\\b",
    ).mapValues { Regex(it.value, RegexOption.IGNORE_CASE) }

    private val selfWords = Regex("^(me|myself|us|you|yourself|him|her|them|it|this|that|baby|babe|daddy|mommy|master|mistress|your .*|me .*|you .*)$", RegexOption.IGNORE_CASE)

    /** A plausible contact for call/sms: not "me", "you", "your good boy"… */
    fun plausibleContact(contact: String, assistantName: String = ""): Boolean {
        val c = contact.trim().trimEnd('.', '!', '?').lowercase()
        if (c.isEmpty() || selfWords.matches(c)) return false
        if (assistantName.isNotBlank() && c == assistantName.trim().lowercase()) return false
        return true
    }

    /** True if [action] (chosen by the model) fits what the user said in [utterance]. */
    fun plausible(action: Action, utterance: String, assistantName: String = ""): Boolean {
        val w = words[action.tool] ?: return true
        if (!w.containsMatchIn(utterance)) return false
        return when (action.tool) {
            "call" -> plausibleContact(action.arg("contact"), assistantName) && !Regex("\\bcall me\\b", RegexOption.IGNORE_CASE).containsMatchIn(utterance)
            "sms" -> plausibleContact(action.arg("contact"), assistantName)
            else -> true
        }
    }

    private val refusal = Regex(
        "^((i'?m sorry|sorry|i apologi[sz]e)[,.!]*\\s*(but\\s*)?(i (can'?t|cannot|won'?t|am unable|'?m unable|am not able|'?m not able)|as an ai|this is)|as an ai|i am an ai|i'?m (just )?(an ai|a language model|an assistant|a (warm, )?private assistant)|" +
            "i (can'?t|cannot|won'?t|am not able to|'?m not able to|am unable to|'?m unable to) (help|assist|do|provide|comply|engage|create|write|continue|fulfil|generate|go)|" +
            "i (don'?t|do not) (feel comfortable|have feelings|have a body))",
        RegexOption.IGNORE_CASE)

    /** The model refused or broke character to say it is an AI. */
    fun isRefusal(text: String): Boolean {
        val t = text.trim().trimStart('"', '*', ' ').replace('’', '\'')
        return refusal.containsMatchIn(t.take(120)) ||
            Regex("\\b(can'?t|cannot|won'?t|unable to) (assist|help) with (that|this)( request)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(t.take(160))
    }
}
