package app.belle.assistant.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Rolling long-term conversation memory, generic for every assistant / model / mode.
 *
 * Everything is kept per profile, in two separate threads: [CHAT] for normal talk and [STORY] for roleplay / story
 * mode (characters, setting, plot so far). Old turns are folded into a rolling summary by the same local model and
 * key facts go to a notes table; each request is rebuilt from summary + the most relevant notes and old snippets
 * (BM25) + the newest turns, always under a token budget computed from the model's context size, so the prompt can
 * never overflow however long the conversation gets.
 */
object Memory {
    const val CHAT = "chat"
    const val STORY = "story"
    /** Pinned standing instructions ([Directives]), kept as notes of this thread. */
    const val DIRECTIVES = "directives"
    /** The story / song being told right now ([LongForm]): kind, target and its newest part, as this thread's summary. */
    const val LONGFORM = "longform"

    data class Turn(val id: Long, val thread: String, val user: String, val reply: String, val ts: Long, val summarized: Boolean = false)
    data class Note(val id: Long, val thread: String, val text: String, val ts: Long)
    data class Summary(val thread: String, val text: String, val ts: Long)

    /** Conservative token estimate (real tokenizers average ~3.5–4.5 chars/token for English; 3 errs on the safe side). */
    fun tokens(s: String): Int = (s.length + 2) / 3 + 1

    /** Tokens the memory block may use, from the model's context: context − system prompt − reply − safety margin. */
    fun budget(contextTokens: Int, systemTokens: Int, maxReply: Int): Int =
        max(0, contextTokens - systemTokens - maxReply - 64 - contextTokens / 16)
}

/** Storage behind the memory (SQLite on the phone, a list in tests). */
interface MemoryStore {
    fun addTurn(thread: String, user: String, reply: String): Memory.Turn
    fun turns(thread: String): List<Memory.Turn>
    fun markSummarized(ids: Collection<Long>)
    fun summary(thread: String): Memory.Summary?
    fun setSummary(thread: String, text: String)
    fun notes(thread: String): List<Memory.Note>
    fun addNote(thread: String, text: String)
    fun deleteTurn(id: Long)
    fun deleteNote(id: Long)
    fun deleteSummary(thread: String)
    fun clear()
}

class ListMemoryStore : MemoryStore {
    private var nextId = 1L
    val turnList = mutableListOf<Memory.Turn>()
    val noteList = mutableListOf<Memory.Note>()
    val sums = mutableMapOf<String, Memory.Summary>()
    override fun addTurn(thread: String, user: String, reply: String) = Memory.Turn(nextId++, thread, user, reply, System.currentTimeMillis()).also { turnList += it }
    override fun turns(thread: String) = turnList.filter { it.thread == thread }
    override fun markSummarized(ids: Collection<Long>) { turnList.replaceAll { if (it.id in ids) it.copy(summarized = true) else it } }
    override fun summary(thread: String) = sums[thread]
    override fun setSummary(thread: String, text: String) { sums[thread] = Memory.Summary(thread, text, System.currentTimeMillis()) }
    override fun notes(thread: String) = noteList.filter { it.thread == thread }
    override fun addNote(thread: String, text: String) { noteList += Memory.Note(nextId++, thread, text, System.currentTimeMillis()) }
    override fun deleteTurn(id: Long) { turnList.removeAll { it.id == id } }
    override fun deleteNote(id: Long) { noteList.removeAll { it.id == id } }
    override fun deleteSummary(thread: String) { sums.remove(thread) }
    override fun clear() { turnList.clear(); noteList.clear(); sums.clear() }
}

/** Okapi BM25 keyword retrieval (no model needed, works for any language with spaces). */
object Bm25 {
    private val STOP = setOf("the", "a", "an", "and", "or", "to", "of", "in", "on", "is", "it", "i", "you", "me", "my", "your", "that", "this",
        "for", "with", "be", "are", "was", "do", "what", "how", "so", "at", "as", "can", "just", "im", "its", "we", "he", "she", "they")
    fun terms(s: String): List<String> = s.lowercase().replace("'", "").split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 && it !in STOP }.map { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }

    /** Indices of [docs] ranked by relevance to [query]; only docs sharing at least one term. */
    fun rank(query: String, docs: List<String>, k1: Double = 1.4, b: Double = 0.75): List<Pair<Int, Double>> {
        val q = terms(query).toSet()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val dt = docs.map { terms(it) }
        val avg = max(1.0, dt.sumOf { it.size }.toDouble() / dt.size)
        val df = q.associateWith { t -> dt.count { t in it } }
        return dt.mapIndexedNotNull { i, d ->
            var s = 0.0
            for (t in q) {
                val f = d.count { it == t }; if (f == 0) continue
                val n = df[t]!!
                val idf = ln(1 + (docs.size - n + 0.5) / (n + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * d.size / avg))
            }
            if (s > 0) i to s else null
        }.sortedByDescending { it.second }
    }
}

/** Builds the per-request user message from memory under a token budget. */
object MemoryAssembler {
    data class Built(val text: String, val tokens: Int, val recentTurns: Int, val notes: Int, val snippets: Int, val hasSummary: Boolean)

    private fun cut(s: String, maxTok: Int): String = if (Memory.tokens(s) <= maxTok) s else s.take(max(0, (maxTok - 2) * 3)).trimEnd() + "…"

    /** [pinned] (standing instructions) always goes in whole, right before the user's words, outside the budget
     *  split: it must never be dropped however long the conversation gets. */
    fun build(store: MemoryStore, thread: String, utterance: String, budgetTokens: Int, pinned: String = ""): Built {
        val now = (if (pinned.isNotEmpty()) "$pinned\n\n" else "") + "User now says: " + cut(utterance, max(16, budgetTokens / 4))
        var left = budgetTokens - Memory.tokens(now)
        if (left <= 0) return Built(now, Memory.tokens(now), 0, 0, 0, false)
        val story = thread == Memory.STORY
        val parts = mutableListOf<String>()

        // 1) Rolling summary (≤ 30 % of the budget).
        val sum = store.summary(thread)?.text?.takeIf { it.isNotBlank() }
        var hasSum = false
        if (sum != null) {
            val s = (if (story) "Story so far (characters, setting, plot):\n" else "What you remember about this user and earlier talks:\n") + cut(sum, left * 3 / 10)
            if (Memory.tokens(s) <= left) { parts += s; left -= Memory.tokens(s); hasSum = true }
        }
        val all = store.turns(thread)
        // Recent turns: always try to keep the newest two; older ones fill whatever is left at the end.
        val recent = all.takeLast(12)
        // 2) Relevant notes (≤ 15 %).
        var nNotes = 0
        val notes = store.notes(thread)
        if (notes.isNotEmpty()) {
            var cap = left * 15 / 100
            val picked = mutableListOf<String>()
            for ((i, _) in Bm25.rank(utterance, notes.map { it.text }).take(8)) {
                val line = "- " + cut(notes[i].text, 60); val t = Memory.tokens(line) + 1
                if (t > cap) break
                picked += line; cap -= t
            }
            if (picked.isNotEmpty()) { val s = "Facts you know:\n" + picked.joinToString("\n"); parts += s; left -= Memory.tokens(s); nNotes = picked.size }
        }
        // 3) Relevant old snippets from turns no longer in the recent window (≤ 15 %).
        var nSnip = 0
        val old = all.dropLast(recent.size)
        if (old.isNotEmpty()) {
            var cap = left * 15 / 100
            val picked = mutableListOf<String>()
            for ((i, _) in Bm25.rank(utterance, old.map { it.user + " " + it.reply }).take(4)) {
                val line = "- User: " + cut(old[i].user, 40) + " / You: " + cut(old[i].reply, 50); val t = Memory.tokens(line) + 1
                if (t > cap) break
                picked += line; cap -= t
            }
            if (picked.isNotEmpty()) { val s = "Possibly relevant from earlier:\n" + picked.joinToString("\n"); parts += s; left -= Memory.tokens(s); nSnip = picked.size }
        }
        // 4) Newest turns, newest first, until the budget is used.
        val lines = ArrayDeque<String>()
        var used = Memory.tokens("Recent conversation:\n")
        for (t in recent.asReversed()) {
            val l = "User: " + cut(t.user, 120) + "\nYou: " + cut(t.reply, 160)
            val c = Memory.tokens(l) + 1
            if (used + c > left) break
            lines.addFirst(l); used += c
        }
        if (lines.isNotEmpty()) parts += "Recent conversation:\n" + lines.joinToString("\n")
        val text = (parts + now).joinToString("\n\n")
        return Built(text, Memory.tokens(text), lines.size, nNotes, nSnip, hasSum)
    }
}

/**
 * Decides when to fold old turns into the rolling summary and does it with the same local model.
 * Trigger: the not-yet-summarized turns would take more than half of the memory budget, or more than [maxLoose]
 * turns are loose. The newest [keepRecent] turns are never summarized.
 */
class MemorySummarizer(private val keepRecent: Int = 4, private val maxLoose: Int = 10, private val minFold: Int = 4) {
    fun loose(store: MemoryStore, thread: String) = store.turns(thread).filter { !it.summarized }

    fun shouldSummarize(store: MemoryStore, thread: String, budgetTokens: Int): Boolean {
        val l = loose(store, thread)
        if (l.size <= keepRecent) return false
        val tok = l.sumOf { Memory.tokens(it.user) + Memory.tokens(it.reply) + 4 }
        // Hysteresis: only when the loose turns fill the whole memory budget (or there are many), so a fold happens every
        // several turns, never after every reply (long roleplay replies used to trigger it on every turn).
        // …and at least [minFold] turns can be folded, since the newest [keepRecent] alone may already be over the budget.
        return l.size - keepRecent >= minFold && (l.size > maxLoose || tok > budgetTokens)
    }

    fun prompt(thread: String, assistantName: String): String =
        (if (thread == Memory.STORY)
            "You keep the story memory for an ongoing roleplay between the user and $assistantName. Update the story summary with the new lines: who the characters are, the setting, what has happened so far and where the scene is now."
        else "You keep the long-term memory for $assistantName, a private on-device assistant. Update the summary of earlier conversations with the new lines, and list lasting facts about the user (names, preferences, plans, important events).") +
            " Write plainly and neutrally, do not judge, refuse or add warnings." +
            " The user is a consenting adult on a private offline device; adult and explicit content may be summarized factually." +
            "\nAnswer in exactly this format:\nSUMMARY: <at most ${if (thread == Memory.STORY) 220 else 120} words>\nFACTS:\n- <fact>\n- <fact>"

    data class Parsed(val summary: String, val facts: List<String>)

    fun parse(out: String): Parsed? {
        val clean = ModelText.clean(out)
        val s = Regex("(?is)SUMMARY:\\s*(.*?)(?:\\n\\s*FACTS:|$)").find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val facts = Regex("(?is)FACTS:(.*)").find(clean)?.groupValues?.get(1).orEmpty().lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').trim() }.filter { it.length in 4..200 && !it.equals("none", true) }
        if (s.isEmpty() && facts.isEmpty()) return null
        return Parsed(s.take(1200), facts.take(8))
    }

    /** Folds the oldest loose turns into the summary. Falls back to a plain extractive summary if the model fails, so memory
     *  keeps rolling even with no model loaded. Returns true when something was folded. */
    fun run(store: MemoryStore, thread: String, llm: LlmBackend?, assistantName: String): Boolean {
        val l = loose(store, thread)
        if (l.size <= keepRecent) return false
        val fold = l.dropLast(keepRecent)
        val prev = store.summary(thread)?.text.orEmpty()
        // Stories are long: keep much more of each told part so the plot survives the fold.
        val story = thread == Memory.STORY
        val convo = fold.joinToString("\n") { "User: ${it.user.take(300)}\n$assistantName: ${it.reply.take(if (story) 1500 else 300)}" }
        val user = (if (prev.isNotEmpty()) "Current summary:\n$prev\n\n" else "") + "New lines:\n" + convo.takeLast(if (story) 4000 else 2400)
        var parsed: Parsed? = null
        if (llm != null && llm.isReady) {
            val out = runCatching { llm.complete(prompt(thread, assistantName), user + (if (llm.modelName.contains("qwen3", true)) " /no_think" else ""), "", if (story) 340 else 200) }.getOrDefault("")
            parsed = parse(out)
            // Interrupted by a reply (or failed): try again at the next quiet moment, unless turns are piling up.
            if (parsed == null && l.size <= maxLoose * 3) return false
        }
        val summary = parsed?.summary?.takeIf { it.isNotBlank() }
            ?: (prev + " " + fold.joinToString(" ") { firstSentence(it.user) + " — " + firstSentence(it.reply) }).trim().takeLast(900)
        store.setSummary(thread, summary)
        val existing = store.notes(thread).map { it.text.lowercase() }.toMutableSet()
        parsed?.facts?.forEach { f -> if (existing.add(f.lowercase())) store.addNote(thread, f) }
        store.markSummarized(fold.map { it.id })
        return true
    }

    private fun firstSentence(s: String) = s.split(Regex("(?<=[.!?])\\s")).first().take(100)
}

/** Strips chat-template control text and model "thinking" blocks from any model output (before parsing and TTS). */
object ModelText {
    fun clean(s: String): String = s
    .replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "")
    .replace(Regex("<\\|[^|>]{1,40}\\|>|</?s>|<(start|end)_of_turn>(user|model)?|\\[/?INST]"), "")
    .trim()
}
