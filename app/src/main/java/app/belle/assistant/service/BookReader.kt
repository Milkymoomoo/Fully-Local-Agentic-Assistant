package app.belle.assistant.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Debug
import app.belle.assistant.avatar.LipSync
import app.belle.assistant.book.Kind
import app.belle.assistant.book.Library
import app.belle.assistant.speech.AudioOut
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * "Read to me": reads a library book aloud word for word with the current assistant's voice (no model rewriting).
 * Sentences are read from disk a few at a time, synthesized at most [AHEAD] ahead on the shared voice thread, and
 * written back to back into one streaming AudioTrack, so there are no gaps between sentences. The position is
 * saved at every sentence. Reading leaves the assistant waiting for its wake word: a wake word pauses the book
 * ("barge-in") and it carries on by itself when the conversation goes back to waiting.
 */
class BookReader(
    private val app: Context,
    private val synth: (String, Float) -> Pair<FloatArray, Int>,
    private val synthExec: ExecutorService,
    private val log: (String) -> Unit,
    private val changed: () -> Unit,
    /** Which voice / settings the audio is made with (a cached sentence is only reused with the same voice). */
    private val voiceKey: () -> String = { "" },
    /** Reading started or stopped (keeps the media-playback service and its notification in step). */
    private val onPlayState: (Boolean) -> Unit = {},
) {
    val lib = Library(File(app.filesDir, "books"))
    private val sp = app.getSharedPreferences("book_reader", Context.MODE_PRIVATE)
    /** Reading speed on top of the assistant's own voice speed. */
    var speed: Float get() = sp.getFloat("speed", 1f); set(v) { sp.edit().putFloat("speed", (Math.round(v.coerceIn(0.6f, 2f) * 100) / 100f)).apply(); changed() }
    var lastBookId: String? get() = sp.getString("last", null); private set(v) { sp.edit().putString("last", v).apply() }

    @Volatile var book: Library.Entry? = null; private set
    @Volatile var chapter = 0; private set
    @Volatile var sentence = 0; private set
    @Volatile var playing = false; private set
    /** Paused by a wake word / push-to-talk: carries on when the assistant goes back to waiting. */
    @Volatile var bargePaused = false; private set
    @Volatile private var pausedAt = 0L
    /** Sleep timer: reading pauses at the first sentence end after this time (0 = off). */
    @Volatile var sleepAt = 0L; private set
    @Volatile var peakPlayPssKb = 0L; private set
    @Volatile var sentencesPlayed = 0; private set
    @Volatile private var announce = false
    @Volatile private var gen = 0
    private var thread: Thread? = null
    private val lock = Object()

    /** A book is open and was in use recently: "stop", "pause", "faster"… mean the book. */
    val active get() = book != null && (playing || bargePaused || System.currentTimeMillis() - pausedAt < 30 * 60_000L)
    val chapterTitle get() = book?.chapters?.getOrNull(chapter)?.title.orEmpty()
    fun nowLine(): String? = book?.let { b -> "${b.title} · $chapterTitle" }

    // ---------- control ----------
    /** Opens [id] (default: the last book) at its saved place. */
    fun open(id: String? = null): Library.Entry? {
        // no book chosen yet (e.g. right after an import): the most recently used / imported one
        val e = (id ?: lastBookId)?.let { lib.get(it) } ?: (if (id == null) lib.list().firstOrNull() else null) ?: return null
        // switching books: the one we leave keeps its own bookmark (saved even when it wasn't playing)
        val wasPlaying = stopThread()
        book?.let { old -> if (!wasPlaying && old.id != e.id && lib.position(old.id)?.finished != true) save() }
        if (book != null && book?.id != e.id) { log("book: bookmark kept for \"${book?.title}\" at $chapterTitle, sentence $sentence"); saveClips(emptyList()) }
        book = e; lastBookId = e.id
        val p = lib.position(e.id)
        if (p == null || p.finished || p.chapter !in e.chapters.indices) { chapter = e.readable.firstOrNull() ?: 0; sentence = 0; announce = true }
        else { chapter = p.chapter; sentence = p.sentence; announce = p.sentence == 0 }
        changed(); return e
    }

    @Volatile private var playAt = 0L
    @Volatile private var dropClips = false
    fun play() {
        val b = book ?: open() ?: return
        bargePaused = false
        synchronized(lock) {
            if (playing) return
            playing = true; playAt = System.currentTimeMillis(); dropClips = false; val my = ++gen; val old = thread
            thread = Thread({ old?.join(4000); track?.let { t -> runCatching { t.pause(); t.flush() } }; runCatching { loop(my) }.onFailure { log("book: reader failed: ${it.message}"); synchronized(lock) { if (gen == my) { playing = false; gen++ } } } }, "belle-book").apply { isDaemon = true; start() }
        }
        log("book: reading \"${b.title}\" from $chapterTitle, sentence $sentence (speed ${speed}x)"); changed()
        onPlayState(true)
    }

    fun pause(reason: String = "paused") {
        bargePaused = false
        if (stopThread()) log("book: $reason at $chapterTitle, sentence $sentence (peak PSS while reading ${peakPlayPssKb / 1024} MB)")
        changed(); onPlayState(false)
    }

    /** Stop and close the book (the place is kept). */
    fun close() { pause("stopped"); book = null; changed() }

    /** Stop (not pause): the bookmark is saved, reading ends, and the sentences made ahead are dropped (memory and
     *  the cache file); the book stays open so Play starts from the bookmark. */
    fun stop() {
        bargePaused = false; resumeOnGain = false
        if (stopThread()) log("book: stopped at $chapterTitle, sentence $sentence (bookmark saved)")
        dropClips = true; save(); saveClips(emptyList()); if (thread?.isAlive != true) releaseTrack(); abandonFocus()
        changed(); onPlayState(false)
    }
    /** Unload: stop, and close the book too (nothing of it stays in memory). Bookmarks and files stay. */
    fun unload() { stop(); val t = book?.title; book = null; if (t != null) log("book: unloaded \"$t\""); changed() }
    private fun releaseTrack() { val t = track ?: return; track = null; trackRate = 0; runCatching { t.stop() }; runCatching { t.release() } }

    fun bargePause() { if (!playing) return; stopThread(); bargePaused = true; log("book: paused for the wake word at $chapterTitle, sentence $sentence"); changed() }
    fun resumeAfterBarge() { if (bargePaused && book != null) { bargePaused = false; log("book: carrying on after the conversation"); play() } }

    /** Moves the reading place; keeps playing if it was. */
    private fun seek(ch: Int, s: Int, title: Boolean) {
        val b = book ?: return
        val wasPlaying = playing; val wasBarge = bargePaused
        stopThread()
        chapter = ch.coerceIn(0, maxOf(0, b.chapters.size - 1)); sentence = maxOf(0, s); announce = title
        save(); changed()
        if (wasPlaying) play() else bargePaused = wasBarge
    }
    fun nextChapter(): Boolean { val b = book ?: return false; val n = b.readable.firstOrNull { it > chapter } ?: return false; seek(n, 0, true); return true }
    fun prevChapter(): Boolean {
        val b = book ?: return false
        // early in a chapter: the one before; otherwise the start of this one
        val n = if (sentence < 8) b.readable.lastOrNull { it < chapter } ?: chapter else chapter
        seek(n, 0, true); return true
    }
    fun restartChapter() = seek(chapter, 0, true)
    fun back(n: Int) { if (sentence == 0) { val b = book ?: return; val p = b.readable.lastOrNull { it < chapter } ?: return; seek(p, maxOf(0, b.chapters[p].sentences - n), false) } else seek(chapter, sentence - n, false) }
    /** "Chapter 5": the chapter titled with that number, else the 5th chapter. */
    fun goTo(n: Int): Boolean {
        val b = book ?: return false
        val byTitle = b.readable.firstOrNull { Regex("\\b$n\\b").containsMatchIn(b.chapters[it].title) || Regex("\\b${roman(n)}\\b").containsMatchIn(b.chapters[it].title) }
        val idx = byTitle ?: b.readable.getOrNull(n - 1) ?: return false
        seek(idx, 0, true); return true
    }
    fun goToChapterIndex(i: Int) = seek(i, 0, true)
    fun faster() { speed += 0.15f; restartSentence() }
    fun slower() { speed -= 0.15f; restartSentence() }
    private fun restartSentence() { if (playing) seek(chapter, sentence, false) }
    fun setSleep(minutes: Int) { sleepAt = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L; log("book: sleep timer ${if (minutes <= 0) "off" else "$minutes min"}"); changed() }

    fun delete(id: String) { if (book?.id == id) close(); lib.delete(id); if (lastBookId == id) lastBookId = null; changed() }

    fun whereAmI(): String {
        val b = book ?: return "No book is open."
        val c = b.chapters[chapter]
        val pct = if (c.sentences > 0) 100 * sentence / c.sentences else 0
        val n = b.readable.indexOf(chapter)
        return "${c.title}${if (n >= 0) ", chapter ${n + 1} of ${b.readable.size}" else ""} of ${b.title}, about $pct percent of the way through it."
    }

    /** The text just read: at most [maxChars] (about 1,500 tokens) ending at the current sentence — never the whole book. */
    fun recentText(maxChars: Int = 6000): String {
        val b = book ?: return ""
        val parts = ArrayList<String>(); var total = 0; var ch = chapter; var s = sentence
        while (total < maxChars) {
            val more = lib.sentences(b.id, ch)?.use { f ->
                var i = minOf(s, f.size) - 1
                while (i >= 0 && total < maxChars) { val t = f[i]; if (t.isNotEmpty()) { parts += t; total += t.length + 1 }; i-- }
                i < 0
            } ?: false
            if (!more) break
            ch = b.readable.lastOrNull { it < ch } ?: break; s = b.chapters[ch].sentences
        }
        return parts.asReversed().joinToString(" ")
    }

    // ---------- playback ----------
    private fun stopThread(): Boolean {
        val was: Boolean
        synchronized(lock) { was = playing; playing = false; gen++ }
        track?.let { t -> runCatching { t.pause(); t.flush() } }
        LipSync.end()
        if (was) { pausedAt = System.currentTimeMillis(); save() }
        return was
    }

    private fun save() { val b = book ?: return; lib.save(b.id, Library.Position(chapter, sentence, System.currentTimeMillis())) }

    private data class Pos(val ch: Int, val idx: Int)   // idx -1 = the chapter title
    /** Audio already made for a sentence: kept when reading stops (in memory and in the cache folder), so the next
     *  play starts at once with it while the voice (re)loads and makes the sentences after it. */
    private class Clip(val book: String, val ch: Int, val idx: Int, val mult: Float, val voice: String, val rate: Int, val pcm: FloatArray)
    @Volatile private var clips: List<Clip> = emptyList()
    private val clipFile get() = File(app.cacheDir, "book_resume.bin")
    private fun saveClips(list: List<Clip>) {
        clips = list
        runCatching {
            if (list.isEmpty()) { clipFile.delete(); return }
            val tmp = File(clipFile.path + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
                o.writeInt(list.size)
                for (c in list) { o.writeUTF(c.book); o.writeInt(c.ch); o.writeInt(c.idx); o.writeFloat(c.mult); o.writeUTF(c.voice); o.writeInt(c.rate); o.writeInt(c.pcm.size)
                    val bb = java.nio.ByteBuffer.allocate(c.pcm.size * 4); bb.asFloatBuffer().put(c.pcm); o.write(bb.array()) } }
            tmp.renameTo(clipFile)
        }
    }
    private fun loadClips(): List<Clip> {
        if (clips.isNotEmpty()) return clips
        return runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(clipFile.inputStream(), 1 shl 16)).use { i ->
                List(i.readInt()) { val b = i.readUTF(); val ch = i.readInt(); val idx = i.readInt(); val mult = i.readFloat(); val v = i.readUTF(); val rate = i.readInt(); val n = i.readInt()
                    val bytes = ByteArray(n * 4); i.readFully(bytes); val pcm = FloatArray(n); java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().get(pcm); Clip(b, ch, idx, mult, v, rate, pcm) } }
        }.getOrDefault(emptyList()).also { clips = it }
    }
    private class Item(val pos: Pos, val text: String, val audio: Future<Pair<FloatArray, Int>?>?)

    private var track: AudioTrack? = null
    private var trackRate = 0
    private var focus: AudioFocusRequest? = null
    @Volatile private var resumeOnGain = false
    /** Paused by a call / another app for a moment: carries on by itself when the audio comes back. */
    val waitingForAudio get() = resumeOnGain

    private fun loop(my: Int) {
        val b = book ?: return
        val open = HashMap<Int, Library.Sentences>()
        fun sents(ch: Int) = open.getOrPut(ch) { open.keys.filter { it < ch - 1 }.forEach { k -> open.remove(k)?.close() }; lib.sentences(b.id, ch) ?: error("chapter $ch missing") }
        fun textAt(p: Pos): String = if (p.idx < 0) b.chapters[p.ch].title + "." else sents(p.ch)[p.idx]
        fun next(p: Pos): Pos? = when {
            p.idx + 1 < sents(p.ch).size -> Pos(p.ch, p.idx + 1)
            else -> b.readable.firstOrNull { it > p.ch }?.let { Pos(it, -1) }
        }
        requestFocus()
        val stops0 = AudioOut.stops
        var pf: Pos? = if (announce || b.chapters[chapter].sentences == 0) Pos(chapter, -1) else Pos(chapter, sentence.coerceAtMost(b.chapters[chapter].sentences - 1))
        if (pf != null && pf.idx >= 0 && pf.idx >= sents(pf.ch).size) pf = next(pf)
        announce = false
        val ahead = ArrayDeque<Item>()
        var lastMem = 0L
        val mult = speed
        val voice = runCatching { voiceKey() }.getOrDefault("")
        // sentences made before the last stop, for this book / place / speed / voice: no waiting for the voice
        val cached = loadClips().filter { it.book == b.id && it.mult == mult && it.voice == voice }.associateBy { Pos(it.ch, it.idx) }
        var current: Item? = null; var currentAudio: Pair<FloatArray, Int>? = null; var first = true
        fun keep() {
            // on stop: the sentence being read (reading resumes at its start) and whatever was made after it
            val out = ArrayList<Clip>()
            val cur = current; val ca = currentAudio
            if (cur != null && ca != null) out += Clip(b.id, cur.pos.ch, cur.pos.idx, mult, voice, ca.second, ca.first)
            for (a in ahead) { val f = a.audio ?: continue; if (!f.isDone || f.isCancelled) break; val r = runCatching { f.get() }.getOrNull() ?: break
                out += Clip(b.id, a.pos.ch, a.pos.idx, mult, voice, r.second, r.first) }
            if (out.isNotEmpty()) saveClips(out)
            else if (cur != null && ca == null) cur.audio?.let { f ->
                // paused while the voice was still making this sentence: let it finish and keep it, so Play starts at once
                val stoppedGen = gen
                Thread({
                    val r = runCatching { f.get(180, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: return@Thread
                    synchronized(lock) { if (gen == stoppedGen && !playing && !dropClips && book?.id == b.id) {
                        saveClips(listOf(Clip(b.id, cur.pos.ch, cur.pos.idx, mult, voice, r.second, r.first))); log("book: sentence finished after the pause, kept for resume") } }
                }, "belle-book-keep").apply { isDaemon = true; start() }
            }
        }
        try {
            while (gen == my) {
                while (ahead.size < AHEAD && pf != null) {
                    val p = pf; val txt = textAt(p); val c = cached[p]
                    ahead.addLast(Item(p, txt, when { txt.isBlank() -> null
                        c != null -> java.util.concurrent.CompletableFuture.completedFuture(c.pcm to c.rate)
                        else -> synthExec.submit(Callable { if (gen != my) null else synth(txt, mult) }) }))
                    pf = next(p)
                }
                val it = ahead.removeFirstOrNull() ?: run { finishBook(b, my); return }
                if (it.pos.ch != chapter) { chapter = it.pos.ch; changed() }
                sentence = maxOf(0, it.pos.idx); save()
                current = it; currentAudio = null
                // wait for the voice, but notice a stop at once (a pause right after play must not leave this thread behind)
                val audio = it.audio?.let { f -> try { var r: Pair<FloatArray, Int>? = null
                        while (gen == my) { try { r = f.get(200, java.util.concurrent.TimeUnit.MILLISECONDS); break } catch (_: java.util.concurrent.TimeoutException) {} }
                        r } catch (e: Exception) { log("book: voice failed on a sentence: ${e.cause?.message ?: e.message}"); null } }
                if (gen != my) break
                currentAudio = audio
                if (first && audio != null) { first = false
                    log("book: first audio ${System.currentTimeMillis() - playAt} ms after play (${if (cached.containsKey(it.pos)) "sentence kept from before" else "voice made it now"})") }
                if (sleepAt in 1..System.currentTimeMillis()) { sleepAt = 0; pause("sleep timer: paused"); break }
                if (AudioOut.stops != stops0) { pause("stopped by the stop button"); break }
                val rate = audio?.second ?: trackRate.takeIf { it > 0 } ?: 24000
                if (audio != null) { if (!write(audio.first, rate, my, stops0)) break; sentencesPlayed++ }
                // natural pauses: a beat after a title, a breath between paragraphs, almost none between sentences
                if (!silence(rate, when { it.pos.idx < 0 -> 900; it.text.isBlank() -> 450; else -> 40 }, my)) break
                val now = System.currentTimeMillis()
                if (now - lastMem > 5000) { lastMem = now; peakPlayPssKb = maxOf(peakPlayPssKb, Debug.getPss()) }
            }
        } finally {
            if (gen != my && !dropClips) runCatching { keep() } else saveClips(emptyList())
            if (dropClips && !playing) releaseTrack()   // stopped / unloaded: the audio buffer goes too
            ahead.forEach { it.audio?.cancel(false) }
            open.values.forEach { it.close() }
            if (!playing) abandonFocus()
        }
    }

    private fun finishBook(b: Library.Entry, my: Int) {
        runCatching { synth("The end of ${b.title}.", speed) }.getOrNull()?.let { write(it.first, it.second, my, AudioOut.stops) }
        silence(trackRate.takeIf { it > 0 } ?: 24000, 600, my)
        synchronized(lock) { if (gen == my) { playing = false; gen++ } }
        lib.save(b.id, Library.Position(chapter, sentence, System.currentTimeMillis(), finished = true))
        log("book: finished \"${b.title}\""); abandonFocus(); changed()
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it; runCatching { it.stop() }; it.release() }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            // ~250 ms: the saved place is never far ahead of what you hear. Must be a whole number of 4-byte float
            // frames (22050 Hz piper voices gave 22050 bytes → "Invalid audio buffer size"); min can be an error code.
            .setBufferSizeInBytes((maxOf(min, rate) + 3) / 4 * 4)
            .build()
        track = t; trackRate = rate; return t
    }

    /** Streams [x] into the track in 50 ms pieces; false = stopped meanwhile. */
    private fun write(x: FloatArray, rate: Int, my: Int, stops0: Int): Boolean {
        val t = ensureTrack(rate)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        t.setVolume(runCatching { AudioOut.volumeProvider() }.getOrDefault(1f).coerceIn(0f, 1f))
        LipSync.begin(x, rate)
        val step = rate / 20; var i = 0
        while (i < x.size) {
            if (gen != my) return false
            if (AudioOut.stops != stops0) { pause("stopped by the stop button"); return false }
            val n = minOf(step, x.size - i)
            val w = t.write(x, i, n, AudioTrack.WRITE_BLOCKING)
            if (w < 0) { log("book: audio write failed ($w)"); return false }
            i += w
        }
        return true
    }
    private fun silence(rate: Int, ms: Int, my: Int): Boolean { if (track == null) return gen == my; return write(FloatArray(rate * ms / 1000), rate, my, AudioOut.stops) }

    private fun requestFocus() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        // still holding it from the last play: asking again with a new request made Android tell the old one it lost
        // the focus, and that paused the book we had just started ("paused: another app took the audio")
        focus?.let { return }
        lateinit var f: AudioFocusRequest
        f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { c ->
                if (focus !== f) return@setOnAudioFocusChangeListener   // an old request: not ours any more
                when (c) {
                    AudioManager.AUDIOFOCUS_LOSS -> { if (playing) pause("paused: another app took the audio"); resumeOnGain = false; abandonFocus() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { resumeOnGain = true; pause("paused: a call or another app needs the audio") }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; play() }
                }
            }.build()
        focus = f; runCatching { am.requestAudioFocus(f) }
    }
    private fun abandonFocus() { val f = focus ?: return; if (resumeOnGain) return; focus = null; runCatching { app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) } }

    companion object {
        const val AHEAD = 3
        fun roman(n: Int): String { if (n <= 0) return "0"; val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1); val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n; val sb = StringBuilder(); for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }; return sb.toString() }
    }
}
