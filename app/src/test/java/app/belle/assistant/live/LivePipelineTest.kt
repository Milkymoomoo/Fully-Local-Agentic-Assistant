package app.belle.assistant.live

import app.belle.assistant.core.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Full-pipeline transcript harness (skipped unless BELLE_GGUF is set): the real RuleParser, Brain (system prompt,
 * memory, grammar), LlmOutputParser and ActionRouter, with llama.cpp running the same LlmCore as the app through
 * tools/host_llm in --serve mode. The phone side mimics AndroidPhoneActions' answers for an unknown contact.
 *   BELLE_HOST_LLM=/path/host_llm BELLE_GGUF=a.gguf,b.gguf BELLE_UTT=utt.txt BELLE_OUT=dir ./gradlew :app:testDebugUnitTest --tests '*LivePipelineTest*'
 */
class LivePipelineTest {
    private class HostLlm(bin: String, val gguf: File) : LlmBackend {
        private val p = ProcessBuilder(bin, gguf.path, "--serve", "4096").redirectError(File(System.getProperty("java.io.tmpdir"), "host_llm_serve.log")).start()
        private val out = BufferedReader(InputStreamReader(p.inputStream))
        private val inp = p.outputStream.bufferedWriter()
        init { check(out.readLine() == "READY") { "host_llm failed to load ${gguf.name}" } }
        override val isReady = true
        override val contextTokens = 4096
        override val modelName = gguf.name
        var lastRaw = ""
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        private fun unesc(s: String): String { val b = StringBuilder(); var i = 0
            while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { val d = s[++i]; b.append(if (d == 'n') '\n' else if (d == 't') '\t' else d) } else b.append(c); i++ }
            return b.toString() }
        override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String {
            inp.write(esc(system) + "\n" + esc(user) + "\n" + esc(grammar) + "\n" + maxTokens + "\n"); inp.flush()
            return unesc(out.readLine() ?: "").also { if (maxTokens > 1) lastRaw = it }
        }
        var lastStream = ""
        var firstPieceMs = -1L
        var lastHit = false
        override fun stream(system: String, user: String, maxTokens: Int, temp: Float, capMs: Long, onText: (String) -> Boolean): LlmBackend.Streamed {
            inp.write(esc(system) + "\n" + esc(user) + "\n\nS" + maxTokens + "\n"); inp.flush()
            // Pieces arrive live, exactly as the phone's token callback delivers them.
            var go = true
            val t0 = System.currentTimeMillis()
            while (true) {
                val line = out.readLine() ?: break
                if (line.startsWith("P")) { if (firstPieceMs < 0) firstPieceMs = System.currentTimeMillis() - t0; if (go) go = onText(unesc(line.drop(1))); continue }
                if (line.startsWith("D")) {
                    val hit = line.getOrNull(1) == 'L'; val text = unesc(line.drop(2))
                    lastStream = text; lastHit = hit; lastRaw = "(streamed ${text.length} chars${if (hit) ", context full" else ""})"
                    return LlmBackend.Streamed(text, hit)
                }
            }
            return LlmBackend.Streamed("", false)
        }
        fun close() { runCatching { inp.close() }; p.destroy() }
    }

    private class Phone : PhoneActions {
        val log = mutableListOf<String>()
        private fun ok(s: String, speech: String = "Done.") = ActionResult(true, speech).also { log += s }
        override fun call(contact: String) = ActionResult(false, "I couldn't find $contact in your contacts.").also { log += "CALL($contact)" }
        override fun sms(contact: String, message: String) = ActionResult(false, "I couldn't find $contact in your contacts.").also { log += "SMS($contact,$message)" }
        override fun openApp(name: String) = ok("OPEN($name)", "I couldn't find an app called $name.")
        override fun setAlarm(hour: Int, minute: Int, label: String) = ok("ALARM($hour:$minute)")
        override fun setTimer(seconds: Int, label: String) = ok("TIMER($seconds)")
        override fun flashlight(state: String) = ok("FLASH($state)")
        override fun volume(level: String, percent: Int?, stream: String) = ok("VOL($level)")
        override fun openSettings(panel: String) = ok("SETTINGS($panel)")
        override fun media(command: String) = ok("MEDIA($command)")
        override fun navigate(destination: String, mode: String) = ok("NAV($destination)")
        override fun reminder(text: String, atEpochMillis: Long) = ok("REMIND($text)")
        override fun calendarEvent(title: String, startEpochMillis: Long?) = ok("CAL($title)")
        override fun saveNote(text: String) = ok("NOTE($text)")
        override fun readNotes() = emptyList<String>()
        override fun deviceInfo(kind: String) = ok("INFO($kind)")
        override fun screen(op: String, target: String) = ok("SCREEN($op,$target)")
        override fun webSearch(query: String) = ok("SEARCH($query)")
        override fun camera(mode: String) = ok("CAMERA($mode)")
    }

    @Test fun transcripts() {
        val ggufs = System.getenv("BELLE_GGUF")?.split(",")?.filter { it.isNotBlank() }
        assumeTrue("set BELLE_GGUF to run", !ggufs.isNullOrEmpty())
        val bin = System.getenv("BELLE_HOST_LLM") ?: "/workspace/host_llm_native/host_llm"
        val utts = File(System.getenv("BELLE_UTT")!!).readLines().filter { it.isNotBlank() }
        val outDir = File(System.getenv("BELLE_OUT") ?: "/tmp/belle_live").apply { mkdirs() }
        val tag = System.getenv("BELLE_TAG") ?: "run"
        val now = ZonedDateTime.of(2026, 11, 17, 21, 0, 0, 0, ZoneId.of("America/New_York"))
        for (path in ggufs!!) {
            val f = File(path)
            val llm = HostLlm(bin, f)
            val roleplay = ModelCatalog.LLMS.firstOrNull { it.fileName == f.name }?.roleplay == true
            val phone = Phone()
            val mem = ListMemoryStore()
            // A populated memory like a phone after days of use: BELLE_PREFILL old turns, already summarized or not.
            val prefill = System.getenv("BELLE_PREFILL")?.toIntOrNull() ?: 0
            for (i in 0 until prefill) mem.addTurn(if (roleplay) Memory.STORY else Memory.CHAT,
                "turn $i: tell me more about the castle by the sea and the dragon who lives there, I love hearing about it",
                "The dragon of the sea castle wakes at dusk, hon, and counts the ships that pass; tonight she is waiting for you on the tower.")
            if (prefill > 0) mem.setSummary(if (roleplay) Memory.STORY else Memory.CHAT, "We have talked for many evenings about a dragon in a sea castle. ".repeat(20))
            val brain = LiveBrain.make({ RuleParser(listOf("Belle")) }, llm, ActionRouter(phone, { true }, { now }), roleplay, mem)
            val sb = StringBuilder("# ${f.name}  (roleplay=$roleplay, tag=$tag)\n\n")
            sb.append("## System prompt\n```\n${brain.system()}\n```\n\n")
            for (u in utts) {
                phone.log.clear(); llm.lastRaw = ""
                val t0 = System.currentTimeMillis()
                val sentences = mutableListOf<String>()
                llm.firstPieceMs = -1L
                var firstSentenceMs = -1L
                // Spoken-duration estimate for the transcript: ~150 words per minute (a typical Piper voice at speed 1.0).
                val r = brain.handle(u) { if (firstSentenceMs < 0) firstSentenceMs = System.currentTimeMillis() - t0; sentences += it; true }
                val st = File(System.getProperty("java.io.tmpdir"), "host_llm_serve.log").readLines().lastOrNull { it.startsWith("[serve]") }.orEmpty()
                val spoken = WebKnowledge.forSpeech(r.speech)
                sb.append("USER: $u\n  source: ${brain.lastSource}  (${System.currentTimeMillis() - t0} ms) $st\n  raw model: ${llm.lastRaw.ifEmpty { "(not called)" }}\n" +
                    (if (brain.lastNote.isNotEmpty()) "  guard: ${brain.lastNote}\n" else "") +
                    "  action: ${r.action?.tool} ${r.action?.args ?: ""}  phone: ${phone.log}\n" +
                    (if (r.streamed) "  STREAMED to TTS: first sentence ready after ${firstSentenceMs} ms; ${sentences.size} sentences, ${LongForm.wordCount(r.speech)} words ≈ ${"%.1f".format(LongForm.wordCount(r.speech) / 150.0)} min of speech; first: «${sentences.firstOrNull()}» last: «${sentences.lastOrNull()}»\n" else "") +
                    "  directives: ${brain.directives()}\n" +
                    "  SPOKEN (TTS): $spoken\n\n")
            }
            llm.close()
            File(outDir, "${tag}_${f.nameWithoutExtension}.md").writeText(sb.toString())
            println(sb)
        }
    }
}
