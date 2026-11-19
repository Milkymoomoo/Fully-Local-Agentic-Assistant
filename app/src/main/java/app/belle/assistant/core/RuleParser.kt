package app.belle.assistant.core

/**
 * Fast, deterministic path for the most common commands. Runs in well under a
 * millisecond, so everyday requests never wait for the LLM. Returns null when
 * it is not confident; the LLM then handles the request.
 */
class RuleParser(private val assistantNames: List<String> = emptyList()) {

    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace(Regex("[“”\"!?,;]"), " ")
            .replace(Regex("\\.(?!\\d)"), " ")
            .replace("wi-fi", "wifi").replace("wi fi", "wifi")
            .replace("flash light", "flashlight")
            .replace(Regex("\\s+"), " ").trim()
        // Strip a wake phrase or the assistant's name if speech-to-text caught it.
        val names = assistantNames.map { it.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }.sortedByDescending { it.length }
        for (n in names) {
            t = t.replace(Regex("^(hey|ok|okay|hi|yo)?\\s*${Regex.escape(n)}\\b\\s*"), "")
        }
        val fillers = listOf(
            "please", "can you", "could you", "would you", "will you", "i want you to", "i need you to",
            "i'd like you to", "go ahead and", "hey", "okay", "ok", "um", "uh", "so",
        )
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (t.startsWith("$f ")) { t = t.removePrefix("$f ").trim(); changed = true }
            }
        }
        t = t.replace(Regex("\\s+(please|for me|thanks|thank you)$"), "").trim()
        return t
    }

    fun parse(raw: String): Action? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        for (rule in rules) rule(t)?.let { return it }
        return null
    }

    private fun a(tool: String, vararg args: Pair<String, String>) = Action(tool, mapOf(*args))

    private val rules: List<(String) -> Action?> = listOf(
        // cancel
        { t -> if (t.matches(Regex("(never ?mind|cancel|cancel that|stop|nothing|forget it|that's all|no thanks)"))) a("cancel") else null },

        // flashlight
        { t ->
            Regex("^(turn|switch|put) (on|off) (the )?(flashlight|torch|light on my phone)$").find(t)?.let { a("flashlight", "state" to it.groupValues[2]) }
                ?: Regex("^(turn|switch) (the )?(flashlight|torch) (on|off)$").find(t)?.let { a("flashlight", "state" to it.groupValues[4]) }
                ?: Regex("^(flashlight|torch)( (on|off))?$").find(t)?.let { a("flashlight", "state" to (it.groupValues[3].ifEmpty { "toggle" })) }
                ?: if (t.matches(Regex("(toggle|use) (the )?(flashlight|torch)"))) a("flashlight", "state" to "toggle") else null
        },

        // timer
        { t ->
            val m = Regex("^(?:set|start|make)?\\s*(?:a|an|the)?\\s*timer (?:for|of) (.+?)(?: called (.+)| for (?:the )?(.+))?$").find(t)
                ?: Regex("^(?:set|start)?\\s*(?:a|an)?\\s*(.+?) timer(?: for (?:the )?(.+))?$").find(t)
                ?: Regex("^count ?down (?:from )?(.+)$").find(t)
            m?.let {
                val secs = NumberWords.parseDurationSeconds(it.groupValues[1]) ?: return@let null
                val label = it.groupValues.drop(2).firstOrNull { g -> g.isNotBlank() }.orEmpty()
                a("set_timer", "seconds" to secs.toString(), "label" to label)
            }
        },

        // reminders
        { t ->
            val inFirst = Regex("^remind me in (.+?) (?:to|that|about) (.+)$").find(t)
            val inLast = Regex("^remind me (?:to |that |about )?(.+?) in (\\S+(?: and a half)? (?:\\S+ )?(?:seconds?|minutes?|hours?|mins?|hrs?)(?: and a half)?)$").find(t)
            val atFirst = Regex("^remind me (?:at|by) (.+?) (?:to|that|about) (.+)$").find(t)
            val atLast = Regex("^remind me (?:to |that |about )?(.+?) at (.+)$").find(t)
            when {
                inFirst != null -> NumberWords.parseDurationSeconds(inFirst.groupValues[1])?.let {
                    a("reminder", "text" to inFirst.groupValues[2], "minutes" to maxOf(1, it / 60).toString(), "hour" to "", "minute" to "")
                }
                inLast != null -> NumberWords.parseDurationSeconds(inLast.groupValues[2])?.let {
                    a("reminder", "text" to inLast.groupValues[1], "minutes" to maxOf(1, it / 60).toString(), "hour" to "", "minute" to "")
                }
                atFirst != null -> NumberWords.parseClock(atFirst.groupValues[1])?.let {
                    a("reminder", "text" to atFirst.groupValues[2], "minutes" to "0", "hour" to it.hour.toString(), "minute" to it.minute.toString())
                }
                atLast != null -> NumberWords.parseClock(atLast.groupValues[2])?.let {
                    a("reminder", "text" to atLast.groupValues[1], "minutes" to "0", "hour" to it.hour.toString(), "minute" to it.minute.toString())
                }
                else -> null
            }
        },

        // alarm
        { t ->
            val m = Regex("^(?:set |make |create )?(?:an |the |my )?alarm (?:for|at) (.+?)(?: called (.+))?$").find(t)
                ?: Regex("^wake me(?: up)?(?: tomorrow| in the morning)? (?:at|by) (.+)$").find(t)
            m?.let {
                val c = NumberWords.parseClock(it.groupValues[1], preferAm = true) ?: return@let null
                a("set_alarm", "hour" to c.hour.toString(), "minute" to c.minute.toString(), "label" to it.groupValues.getOrNull(2).orEmpty())
            }
        },

        // calls
        { t ->
            Regex("^(?:call|phone|dial|ring|facetime|video call) (.+?)(?: on speaker(?:phone)?| now| back)?$").find(t)?.let {
                val who = it.groupValues[1].removePrefix("up ").trim()
                // "call me your good boy", "call me baby", "call yourself…": a name, not a phone call (the model answers).
                if (who.isBlank() || !Intent.plausibleContact(who)) null else a("call", "contact" to who)
            }
        },

        // sms
        { t ->
            (Regex("^(?:send )?(?:a )?(?:text|message|sms|txt)(?: message)? (?:to )?(.+?) (?:saying|that says|that|telling (?:him|her|them)) (.+)$").find(t)
                ?: Regex("^(?:text|message) (.+?) (?:and say|and tell (?:him|her|them)) (.+)$").find(t))?.let {
                a("sms", "contact" to it.groupValues[1], "message" to it.groupValues[2])
            }
        },

        // device info
        { t ->
            when {
                t.matches(Regex("(what time is it|what's the time|what is the time|time|tell me the time|what time it is)( now| right now)?")) -> a("device_info", "kind" to "time")
                t.matches(Regex("(what's the date|what is the date|what day is it|what's today's date|what is today|today's date|what's today)( today)?")) -> a("device_info", "kind" to "date")
                !t.contains("setting") && t.matches(Regex(".*\\b(battery|charge)\\b.*")) && t.matches(Regex("(what's|what is|how much|how's|check|battery|my|show).*")) -> a("device_info", "kind" to "battery")
                t.matches(Regex("(how much )?(storage|space)( (is )?left| do i have( left)?)?|how much (storage|space).*")) -> a("device_info", "kind" to "storage")
                else -> null
            }
        },

        // settings panels & toggles (apps cannot flip wifi/bluetooth directly on Android 10+, so open the panel)
        { t ->
            val panels = mapOf(
                "wifi" to "wifi", "bluetooth" to "bluetooth", "internet" to "internet", "mobile data" to "mobile_data",
                "data" to "mobile_data", "nfc" to "nfc", "location" to "location", "gps" to "location", "battery" to "battery",
                "display" to "display", "brightness" to "display", "sound" to "sound", "airplane mode" to "airplane",
                "airplane" to "airplane", "apps" to "apps", "accessibility" to "accessibility", "assistant" to "assistant",
            )
            val m = Regex("^(?:open |show |go to |take me to )?(?:the |my )?(.+?) settings$").find(t)
                ?: Regex("^(?:turn|switch) (?:on|off) (?:the |my )?(wifi|bluetooth|mobile data|data|nfc|location|gps|airplane mode)$").find(t)
                ?: Regex("^(?:turn|switch) (?:the |my )?(wifi|bluetooth|mobile data|nfc|location|gps|airplane mode) (?:on|off)$").find(t)
                ?: Regex("^(?:open |show )?(?:the )?(wifi|bluetooth|internet|volume) (?:panel|menu)$").find(t)
            when {
                t.matches(Regex("(open |show |go to )?(the )?(phone |my )?settings")) -> a("open_settings", "panel" to "main")
                m != null -> panels[m.groupValues[1]]?.let { a("open_settings", "panel" to it) }
                    ?: if (m.groupValues[1] == "volume") a("open_settings", "panel" to "volume") else null
                else -> null
            }
        },

        // volume
        { t ->
            val stream = when {
                t.contains("ring") -> "ring"; t.contains("alarm") -> "alarm"; t.contains("notification") -> "notification"
                else -> "media"
            }
            val pct = Regex("(?:volume|it|sound) to (\\d{1,3})(?: percent|%)?").find(NumberWords.digitize(t))
            when {
                pct != null -> a("volume", "level" to "set", "percent" to pct.groupValues[1].toInt().coerceIn(0, 100).toString(), "stream" to stream)
                t.matches(Regex("(turn |crank )?(the )?(\\w+ )?(volume|it|sound|music) up|louder|(increase|raise) (the )?(\\w+ )?volume|volume up|make it louder")) -> a("volume", "level" to "up", "percent" to "", "stream" to stream)
                t.matches(Regex("(turn )?(the )?(\\w+ )?(volume|it|sound|music) down|quieter|softer|(decrease|lower) (the )?(\\w+ )?volume|volume down|make it quieter")) -> a("volume", "level" to "down", "percent" to "", "stream" to stream)
                t.matches(Regex("(mute|silence)( (the )?(phone|volume|sound|it|ringer|media))?")) -> a("volume", "level" to "mute", "percent" to "", "stream" to stream)
                t.matches(Regex("unmute( (the )?(phone|volume|sound|it|ringer|media))?")) -> a("volume", "level" to "unmute", "percent" to "", "stream" to stream)
                t.matches(Regex("(max|maximum|full) volume|volume (all the way up|to max|max)|turn it all the way up")) -> a("volume", "level" to "max", "percent" to "", "stream" to stream)
                else -> null
            }
        },

        // media
        { t ->
            when {
                t.matches(Regex("(pause|pause (the )?(music|song|video|it|playback))")) -> a("media", "command" to "pause")
                t.matches(Regex("(resume|play|continue|unpause)( (the )?(music|song|video|it|playback))?")) -> a("media", "command" to "play")
                t.matches(Regex("(next|skip)( (song|track|this|this song|it))?|next one|play (the )?next (song|track)")) -> a("media", "command" to "next")
                t.matches(Regex("(previous|last|go back a) (song|track)|play (the )?(previous|last) (song|track)|previous")) -> a("media", "command" to "previous")
                t.matches(Regex("stop (the )?(music|song|playback|video|playing)")) -> a("media", "command" to "stop")
                else -> null
            }
        },

        // screen / accessibility
        { t ->
            when {
                t.matches(Regex("go back|back|press back")) -> a("screen", "op" to "back", "target" to "")
                t.matches(Regex("go home|home( screen)?|go to (the )?home ?screen|press home")) -> a("screen", "op" to "home", "target" to "")
                t.matches(Regex("(show |open )?(my )?recent( apps)?|recents|switch apps|app switcher")) -> a("screen", "op" to "recents", "target" to "")
                t.matches(Regex("(open |show |pull down )(my |the )?notifications|notifications")) -> a("screen", "op" to "notifications", "target" to "")
                t.matches(Regex("(open |show )(the )?quick settings|quick settings")) -> a("screen", "op" to "quick_settings", "target" to "")
                t.matches(Regex("scroll( down)?|page down")) -> a("screen", "op" to "scroll_down", "target" to "")
                t.matches(Regex("scroll up|page up")) -> a("screen", "op" to "scroll_up", "target" to "")
                t.matches(Regex("(read|what's on|what is on) (the |my )?screen|read (this|it)( to me)?|read (this )?page")) -> a("screen", "op" to "read_screen", "target" to "")
                t.matches(Regex("lock (the |my )?(phone|screen|device)")) -> a("screen", "op" to "lock", "target" to "")
                t.matches(Regex("(take a |grab a )?screenshot|screen ?shot")) -> a("screen", "op" to "screenshot", "target" to "")
                else -> Regex("^(?:tap|click|press|hit|select|touch)(?: on)? (?:the )?(.+?)(?: button| link| icon| tab)?$").find(t)?.let {
                    a("screen", "op" to "tap", "target" to it.groupValues[1])
                } ?: Regex("^long press(?: on)? (?:the )?(.+)$").find(t)?.let {
                    a("screen", "op" to "long_press", "target" to it.groupValues[1])
                } ?: Regex("^(?:type|enter|write|input) (.+)$").find(t)?.let {
                    a("screen", "op" to "type", "target" to it.groupValues[1])
                }
            }
        },

        // camera
        { t ->
            when {
                t.matches(Regex("take a selfie|selfie( mode)?|open (the )?front camera")) -> a("camera", "mode" to "selfie")
                t.matches(Regex("(record|take|shoot) a video|video camera|start recording( video)?")) -> a("camera", "mode" to "video")
                t.matches(Regex("take a (picture|photo|pic)|open (the )?camera|camera")) -> a("camera", "mode" to "photo")
                else -> null
            }
        },

        // notes
        { t ->
            when {
                t.matches(Regex("(read|show|what are|list|read me) (all )?(of )?my notes")) -> a("read_notes")
                else -> (Regex("^(?:take|make|save|write|jot down|add) (?:a )?note(?: that| saying|:)? (.+)$").find(t)
                    ?: Regex("^note (?:that |down )?(.+)$").find(t))?.let { a("note", "text" to it.groupValues[1]) }
            }
        },

        // navigation
        { t ->
            (Regex("^(?:navigate|directions|get directions|give me directions|take me|drive me|drive|route me|how do i get) (?:to )(.+)$").find(t))?.let {
                a("navigate", "destination" to it.groupValues[1], "mode" to if (t.contains("walk")) "walk" else "drive")
            } ?: Regex("^(?:walk|walking directions) to (.+)$").find(t)?.let {
                a("navigate", "destination" to it.groupValues[1], "mode" to "walk")
            } ?: (Regex("^(?:show me |find |show )(.+?) on (?:the |a )?map$").find(t)
                ?: Regex("^where(?:'s| is) (?:the )?(?:nearest|closest) (.+)$").find(t)
                ?: Regex("^find (?:a |the )?(?:nearest |closest )?(.+?) near(?:by| me)$").find(t))?.let {
                a("navigate", "destination" to it.groupValues[1], "mode" to "map")
            }
        },

        // news
        { t ->
            when {
                t.matches(Regex("(what's|what is|tell me|give me|read me|play)? ?(the )?(latest |today's |top )?(news|headlines)( today| right now| for today)?|what's (happening|going on) in the (world|news)")) -> a("news", "topic" to "")
                else -> (Regex("^(?:what's |what is |tell me |give me )?(?:the )?(?:latest )?(?:news|headlines) (?:about|on|for|regarding) (.+)$").find(t)
                    ?: Regex("^(?:what's |what is )?(?:the )?latest (?:on|about) (.+)$").find(t))?.let { a("news", "topic" to it.groupValues[1]) }
            }
        },

        // web: "search X in the browser" opens the browser; other searches are answered in-app
        { t ->
            Regex("^(?:search|google|look up|search for)(?: for)? (.+?) (?:in|on) (?:the |my )?browser$").find(t)?.let {
                a("web_search", "query" to it.groupValues[1])
            } ?: Regex("^(?:search|look up|search for|search the web for|web search|search online for|look online for|find out|check online)(?: for| about)? (.+)$").find(t)?.let {
                a("web_answer", "query" to it.groupValues[1])
            }
        },

        // open app (last: it is the most generic)
        { t ->
            Regex("^(?:open|launch|start|run|load|fire up|bring up|show me|switch to|go to) (?:the |my |up )?(.+?)(?: app| application)?$").find(t)?.let {
                val name = it.groupValues[1]
                if (name.isBlank() || name.split(" ").size > 4) null else a("open_app", "app" to name)
            }
        },
    )
}
