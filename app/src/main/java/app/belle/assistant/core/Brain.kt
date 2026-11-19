package app.belle.assistant.core

/** Anything that can run the local LLM (llama.cpp on the phone, or a fake in tests). */
interface LlmBackend {
    val isReady: Boolean
    fun complete(system: String, user: String, grammar: String, maxTokens: Int): String
    /** Context window in tokens the model was loaded with (the memory budget is computed from it). */
    val contextTokens: Int get() = 2048
    val modelName: String get() = ""

    /** Result of [stream]: the whole raw text, whether it stopped at the token/context limit (not at end-of-turn). */
    data class Streamed(val text: String, val hitLimit: Boolean)

    /**
     * Free text (no grammar), sampled at [temp], handed to [onText] piece by piece as it is generated; [onText]
     * returning false stops generation. [maxTokens] is shrunk to what the context has left; [capMs] is a runaway-only
     * time cap. The default (fakes, tests) runs [complete] and hands over the text in one piece.
     */
    fun stream(system: String, user: String, maxTokens: Int, temp: Float, capMs: Long, onText: (String) -> Boolean): Streamed {
        // Like the native engine: a free-text reply takes whatever room the context has left.
        val room = contextTokens - Memory.tokens(system) - Memory.tokens(user) - 8
        val out = complete(system, user, "", if (room >= 64) minOf(maxTokens, room) else maxTokens)
        if (out.isNotEmpty()) onText(out)
        return Streamed(out, false)
    }
}

/**
 * Turns one utterance into a spoken reply: rule fast-path first, then the local
 * LLM with a grammar-constrained tool call. Also handles yes/no confirmation
 * and one round of follow-up questions ("Who should I call?" -> "Mom").
 */
class Brain(
    private val rules: () -> RuleParser,
    private val llm: () -> LlmBackend?,
    private val router: ActionRouter,
    private val assistantName: () -> String,
    private val sleepPhrases: () -> List<String> = { emptyList() },
    /** The profile's editable system-prompt sections (personality, style, how to address the user, extra). */
    private val persona: () -> PersonaPrompt = { PersonaPrompt.defaults(assistantName()) },
    /** What to say when nothing understood the request and no model ran (names a missing file, never "none" wrongly). */
    private val noModelSpeech: (() -> String)? = null,
    /** The loaded model is a roleplay / story fine-tune: adds the story persona to the prompt. */
    private val roleplay: () -> Boolean = { false },
    /** Long-term memory for the active profile, or null when the user turned memory off. */
    private val memory: () -> MemoryStore? = { null },
    /** Called after each remembered turn so the owner can fold old turns into the summary off the voice path. */
    private val onRemembered: (thread: String) -> Unit = {},
    private val onMemoryError: (Throwable) -> Unit = {},
) {
    val thread: String get() = if (roleplay()) Memory.STORY else Memory.CHAT
    /** Last assembled memory block (for diagnostics / tests). */
    var lastBuilt: MemoryAssembler.Built? = null
        private set
    /** Memory block size: what fits the context, but never more than [MAX_MEMORY_TOKENS] — every token of it is
     *  re-read by the model on each reply, and on a phone CPU a big block alone takes many seconds. */
    fun memoryBudget(l: LlmBackend, maxReply: Int = MAX_REPLY_TOKENS) = minOf(MAX_MEMORY_TOKENS, Memory.budget(l.contextTokens, Memory.tokens(system()), maxReply))

    fun system() = Tools.fullSystemPrompt(assistantName(), persona(), roleplay())

    /** What happened on the last model turn (for diagnostics and the transcript harness). */
    var lastNote: String = ""
        private set

    /** Recent turns, given to the LLM so follow-ups ("and tomorrow?") make sense. */
    private val history = ArrayDeque<Pair<String, String>>()
    fun resetContext() { history.clear(); recentOpenings.clear(); pending = null; needs = null; ramLongForm = null }

    /** Last time the model produced something (the reply watchdog extends its deadline while this moves). */
    @Volatile var lastProgressAt: Long = 0L
        private set

    // ----- pinned directives -----
    private val ramDirectives = mutableListOf<String>()
    /** Standing instructions in force (oldest first): stored with the memory when it is on, else kept for this session. */
    fun directives(): List<String> = memory()?.let { m -> runCatching { m.notes(Memory.DIRECTIVES).sortedBy { it.id }.map { it.text } }.getOrNull() }
        ?: synchronized(ramDirectives) { ramDirectives.toList() }

    private fun updateDirectives(text: String) {
        val cs = Directives.parseAll(text); if (cs.isEmpty()) return
        val cur = directives()
        val next = cs.fold(cur) { acc, c -> Directives.apply(acc, c) }
        if (next == cur) return
        synchronized(ramDirectives) { ramDirectives.clear(); ramDirectives.addAll(next) }
        memory()?.let { m -> runCatching { m.notes(Memory.DIRECTIVES).forEach { m.deleteNote(it.id) }; next.forEach { m.addNote(Memory.DIRECTIVES, it) } }.onFailure(onMemoryError) }
        directiveNote = "directives: ${next.size}"
    }
    private var directiveNote = ""

    // ----- the story / song being told -----
    data class ActiveLong(val kind: LongForm.Kind, val tail: String)
    private var ramLongForm: ActiveLong? = null
    fun activeLongForm(): ActiveLong? {
        memory()?.let { m -> runCatching { m.summary(Memory.LONGFORM)?.text }.getOrNull()?.let { t ->
            val kind = runCatching { LongForm.Kind.valueOf(t.substringBefore('\n')) }.getOrNull()
            if (kind != null) return ActiveLong(kind, t.substringAfter('\n'))
        } }
        return ramLongForm
    }
    private fun setActiveLongForm(a: ActiveLong) {
        ramLongForm = a
        memory()?.let { m -> runCatching { m.setSummary(Memory.LONGFORM, a.kind.name + "\n" + a.tail) }.onFailure(onMemoryError) }
    }

    /** True if the utterance is one of the user's sleep phrases ("goodnight belle", "that's all"...). */
    fun isSleep(utterance: String): Boolean {
        val u = norm(utterance)
        if (u.isEmpty()) return false
        return sleepPhrases().map { norm(it) }.filter { it.isNotEmpty() }.any { p ->
            u == p || u.endsWith(" $p") || (u.startsWith("$p ") && u.removePrefix(p).trim().split(" ").size <= 2)
        }
    }

    private fun norm(s: String) = s.lowercase().replace("’", "'").replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private var pending: Action? = null
    private var needs: Pair<Action, String>? = null
    var lastSource: String = ""
        private set

    private val yes = Regex("^(yes|yeah|yep|yup|sure|do it|send it|go ahead|ok|okay|correct|confirm|please do|absolutely)\\b.*")
    private val no = Regex("^(no|nope|nah|don't|do not|cancel|stop|never ?mind|wait)\\b.*")

    val expectsReply: Boolean get() = pending != null || needs != null

    /**
     * [speech], when given, receives a long-form reply sentence by sentence while it is still being generated
     * (returning false = the user stopped it); the returned [Reply] is then marked [Reply.streamed].
     */
    fun handle(utterance: String, speech: ((String) -> Boolean)? = null): Reply {
        val text = utterance.trim()
        val norm = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (isSleep(text)) {
            lastSource = "sleep"
            resetContext()
            return Reply(listOf("Okay, goodnight.", "Alright, I'll be here if you need me.", "Talk to you later.").random(), endSession = true)
        }
        pending?.let { p ->
            pending = null
            return when {
                yes.matches(norm) -> { lastSource = "confirm"; router.execute(p, confirmed = true).also(::remember) }
                no.matches(norm) -> { lastSource = "confirm"; Reply("Okay, I won't.") }
                else -> handle(text)
            }
        }
        needs?.let { (action, slot) ->
            needs = null
            if (!no.matches(norm)) fillSlot(action, slot, text)?.let { filled ->
                lastSource = "followup"
                return router.execute(filled).also(::remember)
            }
        }
        if (text.isEmpty()) return Reply("I didn't hear anything.")
        directiveNote = ""
        updateDirectives(text)
        val l0 = llm()
        if (l0 != null && l0.isReady) {
            val kind = LongForm.request(text)
            val active = if (kind == null && LongForm.isContinue(text)) activeLongForm() else null
            if (kind != null || active != null) return longForm(l0, text, kind, active, speech)
        }
        val action = rules().parse(text)?.also { lastSource = "rules" }
            ?: askLlm(text)?.also { lastSource = "llm" }
        if (action == null) {
            lastSource = "none"
            val l = llm()
            return Reply(if (l == null || !l.isReady)
                (noModelSpeech?.invoke() ?: "Sorry, I didn't understand that. Download a language model in settings so I can understand more.")
            else "Sorry, I didn't understand that.")
        }
        return router.execute(action).also(::remember).also { addTurn(text, it.speech) }
    }

    /** Openings of the last replies (normalized), to catch a small model parroting an earlier answer. */
    private val recentOpenings = ArrayDeque<String>()
    private fun opening(s: String) = norm(s).take(60)
    private fun noteSpoken(reply: String) { val o = opening(reply); if (o.length < 20) return; recentOpenings.addLast(o); while (recentOpenings.size > 12) recentOpenings.removeFirst() }
    private fun isParrot(reply: String): Boolean { val o = opening(reply); return o.length >= 20 && recentOpenings.any { it == o || (o.length >= 40 && it.startsWith(o.take(40))) } }

    private fun addTurn(user: String, reply: String) {
        noteSpoken(reply)
        history.addLast(user to reply)
        while (history.size > 4) history.removeFirst()
        val m = memory() ?: return
        val th = thread
        runCatching { m.addTurn(th, user, ModelText.clean(reply)) }.onSuccess { onRemembered(th) }.onFailure(onMemoryError)
    }

    /** Pre-computes the system prompt in the LLM's KV cache so the first real request is fast. */
    fun warm() {
        val l = llm() ?: return
        if (l.isReady) l.complete(system(), "hi", Tools.grammar(), 1)
    }

    companion object {
        const val MAX_REPLY_TOKENS = 200
        const val MAX_MEMORY_TOKENS = 600
        /** Long-form: room left for the text after the prompt (each segment fills what the context has left). */
        const val LONG_REPLY_TOKENS = 1600
        const val LONG_MEMORY_TOKENS = 400
        /** Runaway-only limits for one long-form reply: a stuck or looping model, never a long story, hits them. */
        const val LONG_SEGMENTS = 8
        const val LONG_CAP_MS = 30 * 60_000L
        const val LONG_MAX_WORDS = 12_000
        const val LONG_TEMP = 0.8f
    }

    /** The user turn for the model: memory (or the short in-RAM history), pinned directives, then the words. */
    private fun userTurn(l: LlmBackend, text: String, thread: String, budget: Int): String {
        val pinned = Directives.render(directives())
        val mem = memory()
        if (mem != null) {
            val built = runCatching { MemoryAssembler.build(mem, thread, text, budget, pinned) }.getOrNull()
            if (built != null) { lastBuilt = built; return built.text }
        }
        val hist = if (history.isEmpty()) "" else "Earlier in this conversation:\n" + history.joinToString("\n") { "User: ${it.first}\nYou: ${it.second.take(200)}" } + "\n\n"
        return hist + (if (pinned.isNotEmpty()) "$pinned\n\n" else "") + (if (hist.isEmpty() && pinned.isEmpty()) text else "User now says: $text")
    }

    /**
     * A story / song / poem / long answer: free text streamed to [speech] sentence by sentence while it is generated,
     * no character cap. When the context fills up mid-story it carries on in a fresh segment from the newest part
     * (context shift), so it runs to its real ending; only runaway limits stop it early.
     */
    private fun longForm(l: LlmBackend, text: String, kind0: LongForm.Kind?, active: ActiveLong?, speech: ((String) -> Boolean)?): Reply {
        lastSource = "longform"
        lastNote = directiveNote
        val kind = kind0 ?: active!!.kind
        val target = LongForm.targetWords(text, kind)
        val name = assistantName()
        val storyThread = Memory.STORY
        val budget = minOf(LONG_MEMORY_TOKENS, Memory.budget(l.contextTokens, Memory.tokens(system()), LONG_REPLY_TOKENS))
        val instr = if (active != null) LongForm.continueInstruction(kind, target, active.tail, name) else LongForm.instruction(kind, target, name)
        var user = userTurn(l, text, if (thread == Memory.STORY || active != null) storyThread else thread, budget) + "\n\n" + instr

        val full = StringBuilder()
        val spoken = ArrayList<String>()
        var stopped = false
        var refusedOnce = false
        var segments = 0
        val t0 = System.currentTimeMillis()
        lastProgressAt = t0
        while (true) {
            val cleaner = StreamCleaner()
            val split = SentenceSplitter()
            var checked = active != null || refusedOnce   // the first sentence is checked for a refusal before it is spoken
            var refused = false
            val segStart = full.length
            fun emit(sentences: List<String>): Boolean {
                for (s0 in sentences) {
                    val sentence = LongForm.speakable(WebKnowledge.forSpeech(s0), name, text, first = spoken.isEmpty()) ?: continue
                    // an opening parroted from an earlier reply ("I'm just a little more than ready to cuddle…") is skipped
                    if (spoken.size < 2 && isParrot(sentence)) continue
                    if (!checked) {
                        checked = true
                        if (Intent.isRefusal(sentence)) { refused = true; return false }
                    }
                    // A model stuck in a loop (the same sentence over and over) ends the reply.
                    if (spoken.takeLast(8).count { it.equals(sentence, true) } >= 2) { lastNote = (lastNote + " loop stopped").trim(); return false }
                    spoken += sentence
                    if (speech != null && !speech(sentence)) { stopped = true; return false }
                }
                return true
            }
            val r = runCatching {
                l.stream(system(), user, LONG_REPLY_TOKENS * 4, LONG_TEMP, LONG_CAP_MS) { piece ->
                    lastProgressAt = System.currentTimeMillis()
                    val c = cleaner.feed(piece)
                    full.append(c)
                    val go = emit(split.feed(c))
                    go && LongForm.wordCount(full.toString()) < LONG_MAX_WORDS && System.currentTimeMillis() - t0 < LONG_CAP_MS
                }
            }.getOrElse { LlmBackend.Streamed("", false) }
            val restText = cleaner.finish()
            full.append(restText)
            if (!refused && !stopped) emit(split.feed(restText) + split.finish())
            if (refused && !refusedOnce) {
                refusedOnce = true
                full.setLength(segStart)
                lastNote = (lastNote + " refusal retried").trim()
                user += "\n\n(Answer as $name, in character, directly and without refusing.)"
                continue
            }
            if (r.text.isBlank() && full.isEmpty() && segments == 0) {
                // The prompt didn't fit (or the model failed): once more with only the instruction and pinned directives.
                val pinned = Directives.render(directives())
                val short = (if (pinned.isNotEmpty()) "$pinned\n\n" else "") + "User now says: $text\n\n" + instr
                if (user != short) { user = short; segments++; continue }
            }
            segments++
            val words = LongForm.wordCount(full.toString())
            if (stopped || !r.hitLimit || segments >= LONG_SEGMENTS || words >= LONG_MAX_WORDS || System.currentTimeMillis() - t0 >= LONG_CAP_MS) break
            // Context full mid-story: continue in a new segment from the newest part (the old KV is replaced).
            lastNote = (lastNote + " segment ${segments + 1}").trim()
            val pinned = Directives.render(directives())
            user = (if (pinned.isNotEmpty()) "$pinned\n\n" else "") + LongForm.continueInstruction(kind, maxOf(150, target - words), LongForm.tail(full.toString()), name)
            full.append(if (full.endsWith(" ") || full.endsWith("\n")) "" else " ")
        }
        val story = full.toString().trim()
        if (story.isEmpty()) return Reply("Sorry, I lost my train of thought. Ask me again?")
        // Proper turn closing: a reply cut short (stopped by the user or a limit) is stored ending on a full sentence.
        val stored = if (stopped) closeTurn(story) else story
        lastNote = (lastNote + " ${LongForm.wordCount(stored)} words, ${spoken.size} sentences, $segments segment(s)").trim()
        setActiveLongForm(ActiveLong(kind, LongForm.tail(stored, 1500)))
        noteSpoken(stored); history.addLast(text to stored.take(400)); while (history.size > 4) history.removeFirst()
        memory()?.let { m ->
            runCatching {
                m.addTurn(storyThread, text, stored)
                if (thread != storyThread) m.addTurn(thread, text, "(${kind.name.lowercase()} told) " + stored.take(300))
            }.onSuccess { onRemembered(storyThread) }.onFailure(onMemoryError)
        }
        return Reply(stored, streamed = speech != null)
    }

    /** Ends a cut-off text on its last whole sentence (or whole word + "…") so the next turn reads a closed reply. */
    fun closeTurn(s: String): String {
        val t = s.trim()
        val i = maxOf(t.lastIndexOf(". "), t.lastIndexOf("! "), t.lastIndexOf("? "), t.lastIndexOf(".\n"))
        if (i > t.length / 3) return t.substring(0, i + 1)
        if (t.last() in ".!?…\"*") return t
        return (if (t.contains(' ')) t.substringBeforeLast(' ') else t) + "…"
    }

    private fun remember(r: Reply) {
        pending = r.pending
        needs = if (r.needs != null && r.action != null) r.action to r.needs else null
    }

    private fun askLlm(text: String): Action? {
        val l = llm() ?: return null
        if (!l.isReady) return null
        lastNote = directiveNote
        lastProgressAt = System.currentTimeMillis()
        // History / memory / pinned directives go in the user turn so the long system prompt stays cached.
        val user = userTurn(l, text, thread, memoryBudget(l))
        var out = l.complete(system(), user, Tools.grammar(), MAX_REPLY_TOKENS)
        // Context shift: if the turn didn't fit (empty output), shrink memory, then keep only the pinned directives —
        // never the system prompt — and retry.
        if (out.isBlank() && user != text && memory() != null) out = l.complete(system(), userTurn(l, text, thread, memoryBudget(l) / 3), Tools.grammar(), MAX_REPLY_TOKENS)
        if (out.isBlank() && user != text) {
            val pinned = Directives.render(directives())
            out = l.complete(system(), if (pinned.isEmpty()) text else "$pinned\n\nUser now says: $text", Tools.grammar(), MAX_REPLY_TOKENS)
        }
        var action = LlmOutputParser.parse(out) ?: return null
        // A phone action that doesn't fit the words (chat, flirting or roleplay turned into "call me"): ask for a reply.
        if (action.tool != "reply" && !Intent.plausible(action, text, assistantName())) {
            lastNote = "rejected ${action.tool} ${action.args}"
            action = LlmOutputParser.parse(l.complete(system(), user, Tools.replyGrammar(), MAX_REPLY_TOKENS)) ?: return action.copy(tool = "reply", args = mapOf("text" to "Mm, say that again for me?"))
        }
        // Small models sometimes repeat an earlier reply word for word instead of answering: ask again, nudged.
        if (action.tool == "reply" && (isParrot(action.arg("text")) || history.any { norm(it.second) == norm(action.arg("text")) && norm(it.second).length > 12 })) {
            lastNote = (lastNote + " repeat retried").trim()
            val again = LlmOutputParser.parse(l.complete(system(), "$user\n\n(Answer this new message directly and differently; do not repeat anything you said before.)", Tools.replyGrammar(), MAX_REPLY_TOKENS))
            if (again != null && again.arg("text").isNotBlank() && norm(again.arg("text")) != norm(action.arg("text"))) action = again
        }
        // A refusal / "I'm just an AI": once more, nudged to answer in character. Whatever comes back is spoken — never silence.
        if (action.tool == "reply" && Intent.isRefusal(action.arg("text"))) {
            val again = LlmOutputParser.parse(l.complete(system(), "$user\n\n(Answer as ${assistantName()}, in character, directly and without refusing.)", Tools.replyGrammar(), MAX_REPLY_TOKENS))
            lastNote = (lastNote + " refusal retried").trim()
            if (again != null && again.arg("text").isNotBlank()) action = again
        }
        if (action.tool == "reply") action = action.copy(args = action.args + ("text" to LongForm.tidy(action.arg("text"))))
        return action
    }

    private fun fillSlot(a: Action, slot: String, text: String): Action? {
        val m = a.args.toMutableMap()
        when (slot) {
            "time" -> {
                val c = NumberWords.parseClock(text, preferAm = true) ?: return null
                m["hour"] = c.hour.toString(); m["minute"] = c.minute.toString()
            }
            "duration" -> m["seconds"] = (NumberWords.parseDurationSeconds(text) ?: return null).toString()
            "when" -> {
                val d = NumberWords.parseDurationSeconds(text)
                if (d != null) m["minutes"] = maxOf(1, d / 60).toString() else {
                    val c = NumberWords.parseClock(text) ?: return null
                    m["minutes"] = "0"; m["hour"] = c.hour.toString(); m["minute"] = c.minute.toString()
                }
            }
            else -> m[slot] = text.trim().trimEnd('.', '!', '?')
        }
        return a.copy(args = m)
    }
}
