package app.belle.assistant.service

import app.belle.assistant.core.Platform
import app.belle.assistant.core.TimedCall
import app.belle.assistant.core.LlmOnDemand
import app.belle.assistant.core.ModelChoice
import app.belle.assistant.avatar.Backgrounds
import app.belle.assistant.SettingsRev
import app.belle.assistant.ui.AssistantWidget
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.core.ProfileRouter
import app.belle.assistant.actions.AndroidPhoneActions
import app.belle.assistant.core.ActionRouter
import app.belle.assistant.core.Brain
import app.belle.assistant.core.Knowledge
import app.belle.assistant.core.ModelCatalog
import app.belle.assistant.core.SearchProvider
import app.belle.assistant.core.Source
import app.belle.assistant.core.WebAnswer
import app.belle.assistant.core.WebKnowledge
import app.belle.assistant.core.VoiceShift
import app.belle.assistant.net.Net
import app.belle.assistant.net.WebViewFetcher
import app.belle.assistant.actions.BelleAccessibilityService
import app.belle.assistant.core.Reply
import app.belle.assistant.core.RuleParser
import app.belle.assistant.book.BookCmd
import app.belle.assistant.book.BookImport
import app.belle.assistant.book.BookCommands
import app.belle.assistant.llm.LlamaEngine
import app.belle.assistant.core.MemorySummarizer
import app.belle.assistant.core.ListenPolicy
import app.belle.assistant.memory.SqlMemoryStore
import app.belle.assistant.models.ModelFiles
import app.belle.assistant.speech.AudioOut
import app.belle.assistant.speech.Chime
import app.belle.assistant.speech.Sounds
import app.belle.assistant.speech.SAMPLE_RATE
import app.belle.assistant.speech.SpeechToText
import app.belle.assistant.speech.TextToSpeech
import app.belle.assistant.speech.WakeWordEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Process-wide assistant: engines, state machine, and the brain. The foreground
 * service feeds it microphone audio; the assist session, avatar, notification,
 * app UI and debug hooks call into it.
 *
 * "Load on demand" (default): only the ~6 MB wake-word spotter stays loaded.
 * On wake, STT loads first (audio is buffered meanwhile so nothing is lost),
 * then TTS and the LLM load in parallel while you speak. After
 * [Prefs.unloadAfterSec] of idleness they are unloaded again.
 */
object AssistantRuntime {
    private const val TAG = "BelleEvent"
    enum class Mode { OFF, LOADING, WAKE, WAKING, LISTEN, BUSY }
    /** What the avatar / edge glow shows. */
    enum class Ui { IDLE, WAKING, LISTENING, THINKING, SPEAKING, SLEEPING }

    /** One line of the conversation screen. */
    data class Turn(val fromUser: Boolean, val text: String, val sources: List<Source> = emptyList(), val time: Long = System.currentTimeMillis(), val note: String? = null)
    val transcript = ArrayList<Turn>()
    private fun addTurn(t: Turn) { synchronized(transcript) { transcript += t; while (transcript.size > 200) transcript.removeAt(0) }; notifyListeners() }
    fun clearTranscript() { synchronized(transcript) { transcript.clear() }; notifyListeners() }

    @Volatile var ui = Ui.IDLE
        private set
    @Volatile var mode = Mode.OFF
        private set
    @Volatile var status = "Not started"
        private set
    /** A conversation is in progress (listening, thinking or speaking). */
    val inSession: Boolean get() = mode == Mode.WAKING || mode == Mode.LISTEN || mode == Mode.BUSY ||
        ui == Ui.LISTENING || ui == Ui.THINKING || ui == Ui.SPEAKING || ui == Ui.WAKING

    private lateinit var app: Context
    private lateinit var prefs: Prefs
    @Volatile private var worker = newWorker()
    private fun newWorker() = Executors.newSingleThreadExecutor { Thread(it, "belle-brain") }
    /** The reply itself runs here, so the conversation thread can time it out ([THINK_LIMIT_MS]) and move on. */
    private val thinker = TimedCall("belle-think")
    /** A reply is being worked out: background model work (memory summary, warm-up) stays off. */
    @Volatile private var busyThinking = false
    private const val SUMMARIZE_IDLE_MS = 20_000L
    /** Hard limit for working out one reply once the model is loaded; then it is cancelled and the user hears so. */
    const val THINK_LIMIT_MS = 100_000L
    /** Longest wait for the thinking model to finish loading before a reply. */
    private const val LOAD_WAIT_S = 150L
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "belle-loader") }
    private val llmLoader = Executors.newSingleThreadExecutor { Thread(it, "belle-llm-loader") }
    private val main = Handler(Looper.getMainLooper())

    private var wake: WakeWordEngine? = null
    @Volatile private var stt: SpeechToText? = null
    @Volatile private var tts: TextToSpeech? = null
    val llm: LlamaEngine by lazy { LlamaEngine() }
    /** Selected vs loaded thinking model (see [LlmOnDemand]): unloading never clears the choice. */
    val gate: LlmOnDemand by lazy {
        LlmOnDemand(
            installed = { ModelFiles.installedLlms(app).map { it.name } },
            profileChoice = { prefs.llmFile },
            globalLast = { ProfileStore.lastLlm(app) },
            loadFile = { name ->
                val f = File(ModelFiles.llmDir(app), name)
                val threads = prefs.llmThreads.takeIf { it > 0 } ?: ModelCatalog.recommendedThreads(Runtime.getRuntime().availableProcessors())
                event("llm: loading $name with $threads threads")
                val t0 = System.currentTimeMillis()
                val ok = llm.load(f, threads)
                if (ok) brain.warm()
                event(if (ok) "llm: ready $name in ${System.currentTimeMillis() - t0} ms (incl. prompt warm-up)" else "llm: FAILED to load $name")
                ok
            },
            unloadFile = { llm.unload() },
        )
    }
    fun modelPick(): ModelChoice.Pick? = if (::app.isInitialized) gate.pick() else null
    @Volatile private var llmFuture: Future<*>? = null
    private lateinit var brain: Brain
    private var listenStart = 0L
    /** Audio actually given to the recogniser since listening began. The silence timeout counts this, not the wall
     *  clock: on a slow phone the recogniser can lag behind the microphone, and a wall-clock timeout would fire
     *  before the command (already spoken, still queued) has been looked at. */
    @Volatile private var listenAudioMs = 0L
    private var heardLogged = false
    @Volatile private var injecting = false
    private val wakeBuffer = ArrayList<FloatArray>()
    /** Set by endSession(); a reply still being computed is then not spoken. */
    @Volatile private var sessionCancelled = false
    @Volatile private var lastTurnAt = 0L
    lateinit var web: WebKnowledge
        private set
    lateinit var webView: WebViewFetcher
        private set

    val log = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    private fun notifyListeners() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    fun event(msg: String) {
        lastProgressAt = System.currentTimeMillis()
        Log.i(TAG, msg)
        synchronized(log) { log.addLast(msg); while (log.size > 80) log.removeFirst() }
        notifyListeners()
    }

    /** The service's recorder is getting exact digital silence (Android blocked background capture / mic taken). */
    @Volatile var micSilenced = false
        private set
    fun setMicSilenced(v: Boolean) { micSilenced = v; notifyListeners() }

    /** Live diagnostics for the "Listening status" panel (read by the UI ~5×/s; cheap volatile writes from the mic thread). */
    object Diag {
        @Volatile var levelDb = -120f            // RMS of the last ~100 ms chunk, dBFS
        @Volatile var peakDb = -120f             // decaying peak
        @Volatile var lastAudioAt = 0L           // last chunk from the recorder
        @Volatile var lastNonZeroAt = 0L         // last chunk that wasn't digital silence
        @Volatile var systemSilenced: Boolean? = null   // AudioManager says Android silences our recorder (null = unknown)
        @Volatile var startedFrom = ""           // what (re)started the microphone service: app, widget, notification, boot…
        @Volatile var micAllowed = true          // false = started from the background on Android 11+, so the mic is muted for us
        @Volatile var lastWake = ""; @Volatile var lastWakeAt = 0L
        @Volatile var lastHeard = ""; @Volatile var lastHeardAt = 0L
        @Volatile var lastKwsTokens = ""; @Volatile var lastKwsAt = 0L
        @Volatile var kwsChunks = 0L             // chunks the wake-word spotter has processed
        fun onChunk(c: FloatArray) {
            var sum = 0.0; for (x in c) sum += x * x
            val db = if (sum <= 0) -120f else (10 * kotlin.math.log10(sum / c.size)).toFloat()
            levelDb = db; peakDb = maxOf(db, peakDb - 1.5f); lastAudioAt = System.currentTimeMillis()
            if (sum > 0) lastNonZeroAt = lastAudioAt
        }
    }

    /** Last time the state machine moved; the watchdog uses it to un-stick a conversation that hangs. */
    @Volatile private var lastProgressAt = System.currentTimeMillis()
    /** The voice is playing a reply (normal or streamed): no listening, no listen timer, no watchdog restart. */
    @Volatile private var speakingNow = false
    @Volatile private var lastSpokeAt = 0L
    private const val STUCK_MS = 180_000L
    private val watchdog = object : Runnable {
        override fun run() {
            main.postDelayed(this, 15_000)
            if (!::prefs.isInitialized) return
            val now = System.currentTimeMillis()
            // A reply that is still generating or speaking (a long story) is progress too; the reply has its own watchdog.
            val progress = maxOf(lastProgressAt, if (::brain.isInitialized) brain.lastProgressAt else 0L, lastSpokeAt)
            val r = ListenPolicy.watchdog(listenCfg(), listening = mode == Mode.LISTEN, busy = mode == Mode.BUSY || mode == Mode.WAKING,
                sinceListenStartMs = if (listenStart > 0) now - listenStart else 0, sinceProgressMs = now - progress,
                speaking = speakingNow || busyThinking)
            if (r == ListenPolicy.Recover.NONE) return
            event("watchdog: stuck in $mode for ${(now - lastProgressAt) / 1000}s, " + if (r == ListenPolicy.Recover.RESTART_LISTENING) "unlimited listening: restarting the listener" else "back to wake words")
            AudioOut.stop(); synchronized(wakeBuffer) { wakeBuffer.clear(); feeding = false }
            lastProgressAt = now
            if (r == ListenPolicy.Recover.RESTART_LISTENING) startListening(followUp = true, quiet = true) else backToWake()
        }
    }

    fun listenCfg() = ListenPolicy.Cfg(prefs.listenTimeoutOn, prefs.listenTimeoutSec, prefs.continuous)

    private fun setUi(u: Ui) { if (ui != u) { ui = u; event("ui=$u") } }
    private fun setMode(m: Mode, s: String) { mode = m; status = s; event("mode=$m $s") }

    fun isDebuggable(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** One-line summary of what is in memory, for the UI. */
    fun loadState(): String {
        if (!::prefs.isInitialized) return ""
        val parts = mutableListOf<String>()
        parts += "wake word " + if (wake != null) "✓" else "–"
        parts += "speech-to-text " + if (stt != null) "✓" else "–"
        parts += "voice " + if (tts != null) "✓" else "–"
        parts += "LLM " + when {
            llm.isReady -> "✓ ${llm.loadedFile}"
            llmFuture?.isDone == false -> "loading…"
            !gate.pick().ok -> gate.pick().note
            else -> "unloaded (${gate.pick().file}, loads when needed)"
        }
        return (if (prefs.loadOnDemand) "On demand (unload after ${prefs.unloadAfterSec}s idle): " else "Always loaded: ") + parts.joinToString(" · ")
    }

    private val widgetRefresh = Runnable { AssistantWidget.updateAll(app) }

    private val initLock = Any()
    /** Its own lock, not the runtime's: the runtime's lock is held while the voice / speech models load (tens of
     *  seconds on a slow phone), and init runs on the main thread from service onCreate / onStartCommand — waiting
     *  there missed the startForeground deadline (crash, found with the book service on the emulator). */
    fun init(ctx: Context) = synchronized(initLock) { initLocked(ctx) }
    private fun initLocked(ctx: Context) {
        Backgrounds.recoverAfterCrash(ctx.applicationContext)
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        AudioOut.volumeProvider = { prefs.voiceVolume }
        AudioOut.audioManager = app.getSystemService(android.media.AudioManager::class.java)
        main.postDelayed(watchdog, 15_000)
        // Any setting change reaches the widget too (debounced so a dragged slider is one update, not hundreds).
        SettingsRev.onChange = { main.removeCallbacks(widgetRefresh); main.postDelayed(widgetRefresh, 700) }
        ProfileStore.ensureDefaults(app)
        val phone = AndroidPhoneActions(app)
        val net = Net(prefs)
        webView = WebViewFetcher(app, prefs)
        web = WebKnowledge(
            fetcher = net,
            providers = { prefs.searchOrder.filter { it !in prefs.searchDisabled }.mapNotNull { SearchProvider.fromId(it) } },
            searxUrl = { prefs.searxUrl },
            feeds = { prefs.newsFeeds },
            llm = { llm },
            pagesToRead = { prefs.pagesToRead },
            searchFetcher = webView,
            showInBrowser = { url ->
                if (prefs.browsingMode == "frontend") main.post {
                    runCatching {
                        app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { event("frontend: no browser to open ($it)") }
                }
            },
            screenReader = {
                if (prefs.browsingMode == "frontend" && prefs.frontendScreenRead) {
                    Thread.sleep(2500)   // let the tab render
                    BelleAccessibilityService.instance?.readScreen()
                } else null
            },
        )
        val knowledge = object : Knowledge {
            override val enabled get() = prefs.internetEnabled
            override fun answer(query: String): WebAnswer {
                status = "Searching the web…"; event("web: searching \"$query\"")
                return web.answer(query).also { event("web: ${it.sources.size} sources via ${web.lastProvider?.label ?: "none"} ${web.errors}") }
            }
            override fun news(topic: String): WebAnswer { status = "Reading the news…"; return web.news(topic) }
        }
        brain = Brain(
            rules = { RuleParser(prefs.wakePhrases + prefs.assistantName) },
            llm = { llm },
            router = ActionRouter(phone, confirmMessages = { prefs.confirmMessages }, knowledge = knowledge),
            assistantName = { prefs.assistantName },
            sleepPhrases = { prefs.sleepPhrases },
            persona = { prefs.persona },
            noModelSpeech = { ModelChoice.noModelSpeech(gate.pick(), gate.lastFailure) },
            roleplay = { ModelCatalog.LLMS.firstOrNull { it.fileName == llm.loadedFile }?.roleplay == true },
            memory = { if (prefs.memoryEnabled) SqlMemoryStore.of(app, prefs.id) else null },
            onMemoryError = { e -> Log.w(TAG, "memory write failed", e); event("memory: write failed: ${e.message}") },
            onRemembered = { th -> scheduleSummarize(th) },
        )
    }

    /** (Re)load after start or a settings change. */
    fun load(ctx: Context, reloadLlm: Boolean = true) {
        init(ctx)
        setMode(Mode.LOADING, "Loading…")
        loader.execute {
            try {
                val t0 = System.currentTimeMillis()
                ModelFiles.ensureBundled(app)
                wake?.release(); wake = WakeWordEngine(app, prefs)
                event("wake word ready in ${System.currentTimeMillis() - t0} ms: ${wake?.phrases}")
                if (wake?.rejected?.isNotEmpty() == true) event("wake: skipped phrases that can't be spelled: ${wake?.rejected}")
                stt?.release(); stt = null
                tts?.release(); tts = null
                clearTransientState("listener (re)started")
                if (reloadLlm) llmLoader.submit { gate.unload() }.get()
                if (!prefs.loadOnDemand) { ensureStt(); ensureTts(); startLlmLoad() }
                backToWake()
            } catch (e: Throwable) {
                Log.e(TAG, "load failed", e)
                setMode(Mode.OFF, "Speech models failed to load: ${e.message}")
            }
        }
    }

    /**
     * Turning the assistant off and on again must really start clean: flags left behind by an interrupted turn
     * (audio parked for replay, an injection, a cancelled session, a reply still marked as thinking) are cleared,
     * and a worker thread stuck in an old turn is replaced. Long-term memory is kept.
     */
    private fun clearTransientState(why: String) {
        val stale = feeding || injecting || busyThinking || sessionCancelled
        synchronized(wakeBuffer) { wakeBuffer.clear(); feeding = false }
        injecting = false; sessionCancelled = false
        if (busyThinking) { llm.cancel(); busyThinking = false; llm.replyDone(); thinker.reset(); val oldW = worker; worker = newWorker(); oldW.shutdownNow() }
        if (stale) event("reset leftover conversation state ($why)")
    }

    /** Rebuild only the wake-word spotter (after editing wake words or sensitivity). */
    fun reloadWakeWords(ctx: Context) {
        init(ctx)
        // e.g. after switching profile: make sure the listener runs if the user wants it on
        if (mode == Mode.OFF) { if (prefs.serviceWanted) AssistantService.start(ctx); return }
        loader.execute {
            val old = wake
            wake = WakeWordEngine(app, prefs)
            old?.release()
            event("wake words: ${wake?.phrases}")
            if (wake?.rejected?.isNotEmpty() == true) event("wake: skipped ${wake?.rejected}")
            if (mode == Mode.WAKE) status = wakeStatus()
            notifyListeners()
        }
    }

    fun wakeRejected(): List<String> = wake?.rejected ?: emptyList()

    @Synchronized private fun ensureStt(): SpeechToText {
        stt?.let { return it }
        val t0 = System.currentTimeMillis()
        return SpeechToText(app).also { stt = it; event("stt loaded in ${System.currentTimeMillis() - t0} ms") }
    }

    private fun ttsQuality() = TextToSpeech.Quality(prefs.ttsNoise, prefs.ttsNoiseW)
    @Volatile private var ttsStale = false
    /** Rebuild the voice on next use (quality sliders or voice changed). Never frees it under a running synth. */
    fun reloadVoice() { ttsStale = true }

    @Synchronized private fun ensureTts(): TextToSpeech {
        if (ttsStale) { ttsStale = false; tts?.release(); tts = null }
        tts?.let { t ->
            // A different assistant (profile) may use a different voice.
            if (t.voiceDir.name == prefs.voiceDir || !File(ModelFiles.voicesDir(app), prefs.voiceDir).isDirectory) return t
            t.release(); tts = null
        }
        val t0 = System.currentTimeMillis()
        // Chosen voice not installed yet? Use any installed one instead of failing.
        val vd = prefs.voiceDir.takeIf { File(ModelFiles.voicesDir(app), it).isDirectory } ?: ModelFiles.installedVoices(app).firstOrNull() ?: prefs.voiceDir
        return TextToSpeech(app, vd, ttsQuality()).also { tts = it; event("tts loaded in ${System.currentTimeMillis() - t0} ms (${it.voiceDir.name})") }
    }

    private fun startLlmLoad() {
        val pick = gate.pick()
        val name = pick.file ?: run { event("llm: ${pick.note}"); return }
        if (llm.isReady && llm.loadedFile == name) return
        if (llmFuture?.isDone == false) return
        if (pick.note != "chosen for this assistant") event("llm: ${pick.note}")
        llmFuture = llmLoader.submit { gate.ensure() }
    }

    /** The system prompt changed (system prompt sections, name): re-fill the model's prompt cache in the
     *  background now, so the next reply doesn't pay for re-reading the whole prompt. */
    private val summarizer = MemorySummarizer()
    @Volatile private var summarizeThread: String? = null
    /** Folds old turns into the rolling summary — only once the conversation has been quiet for a while, as
     *  low-priority model work that any new reply cancels at once (llama abort), so it is never on the reply path. */
    private fun scheduleSummarize(thread: String) {
        summarizeThread = thread
        main.removeCallbacks(summarizeRunnable)
        main.postDelayed(summarizeRunnable, SUMMARIZE_IDLE_MS)
    }
    private val summarizeRunnable = Runnable {
        val thread = summarizeThread ?: return@Runnable
        if (busyThinking) { scheduleSummarize(thread); return@Runnable }
        val store = SqlMemoryStore.of(app, prefs.id)
        val name = prefs.assistantName
        llmLoader.execute {
            runCatching {
                val budget = if (llm.isReady) brain.memoryBudget(llm) else 600
                if (!busyThinking && summarizer.shouldSummarize(store, thread, budget)) {
                    val t0 = System.currentTimeMillis()
                    val folded = llm.background { summarizer.run(store, thread, if (llm.isReady) llm else null, name) }
                    event(if (folded) "memory: folded old $thread turns into the summary in ${System.currentTimeMillis() - t0} ms"
                        else "memory: summary postponed (interrupted by a reply)")
                    if (llm.isReady && !busyThinking) llm.background { brain.warm() }
                }
            }.onFailure { Log.w(TAG, "memory summarize failed", it) }
        }
    }

    /**
     * "Reset conversation": the recovery button. Stops a reply that is still being worked out (and any background
     * model work), drops the short-term context and pending questions, replaces a stuck worker, and goes back to
     * waiting for the wake word. Long-term memory is kept (it has its own Clear buttons).
     */
    fun unstick(ctx: Context) {
        init(ctx)
        event("user: reset conversation")
        sessionCancelled = true
        main.removeCallbacks(summarizeRunnable)
        llm.cancel()
        AudioOut.stop()
        if (readerReady) reader.pause("stopped (reset)")
        synchronized(wakeBuffer) { wakeBuffer.clear(); feeding = false }
        thinker.reset()
        val oldW = worker; worker = newWorker(); oldW.shutdownNow()
        busyThinking = false; llm.replyDone()
        if (::brain.isInitialized) runCatching { brain.resetContext() }
        clearTranscript()
        if (mode == Mode.OFF || mode == Mode.LOADING) { setUi(Ui.IDLE); return }
        worker.execute { sessionCancelled = false; backToWake() }
    }

    /** Forget the in-RAM recent lines too (after the user clears memory). */
    fun resetConversation() { if (::brain.isInitialized) brain.resetContext() }

    fun rewarm() { if (!::app.isInitialized) return; llmLoader.execute { if (llm.isReady && !busyThinking) runCatching { llm.background { brain.warm() } } } }

    fun reloadLlm(ctx: Context) {
        init(ctx)
        // one task on the loader thread: unload, then (unless on-demand) load the new choice — no check-then-unload race
        val eager = !prefs.loadOnDemand
        llmFuture = llmLoader.submit { gate.unload(); if (eager) gate.ensure() }
        if (!eager) event("llm: will load on next wake")
    }

    private fun unloadHeavy(force: Boolean = false) {
        if (!prefs.loadOnDemand || (!force && mode != Mode.WAKE)) return
        main.removeCallbacks(unloadRunnable)
        if (readerBusy) { main.postDelayed(unloadRunnable, prefs.unloadAfterSec * 1000L); return }   // the voice is reading a book
        loader.execute {
            stt?.release(); stt = null
            tts?.release(); tts = null
            llmFuture?.let { runCatching { it.get(30, TimeUnit.SECONDS) } }
            llmLoader.submit { gate.unload() }.get()
            event("idle: unloaded speech-to-text, voice and LLM (wake word still listening)")
        }
    }
    private val unloadRunnable = Runnable { unloadHeavy() }

    /** "Load on demand" switched: applied in place (no listener restart). Off = load everything now; on = unload when idle. */
    fun applyLoadOnDemand(ctx: Context) {
        init(ctx)
        if (mode == Mode.OFF || mode == Mode.LOADING) return
        if (!prefs.loadOnDemand) { main.removeCallbacks(unloadRunnable); loader.execute { runCatching { ensureStt(); ensureTts() } }; startLlmLoad() }
        else if (mode == Mode.WAKE) { main.removeCallbacks(unloadRunnable); main.postDelayed(unloadRunnable, prefs.unloadAfterSec * 1000L) }
        event("load on demand: ${prefs.loadOnDemand} (applied without restarting the listener)")
    }

    /** Called by the service with each ~100 ms of microphone audio. */
    /** True while audio captured during wake-up is replayed into speech recognition; live audio queues behind it. */
    @Volatile private var feeding = false

    fun onAudio(chunk: FloatArray, fromInjection: Boolean = false, fromFeed: Boolean = false) {
        if (injecting && !fromInjection && !fromFeed) return
        if (feeding && !fromFeed) { synchronized(wakeBuffer) { wakeBuffer += chunk }; return }
        when (mode) {
            Mode.WAKE -> {
                if (!prefs.wakeWordEnabled) return
                val w = wake ?: return
                Diag.kwsChunks++
                val hit = w.accept(chunk)
                w.lastTokens?.let { Diag.lastKwsTokens = it; Diag.lastKwsAt = System.currentTimeMillis() }
                if (hit == null) return
                Diag.lastWake = hit; Diag.lastWakeAt = System.currentTimeMillis()
                // Exactly one assistant answers: the active one, or (multi mode) the owner of this phrase.
                val who = ProfileRouter.resolve(hit, ProfileStore.wakeTable(app), ProfileStore.activeId(app), ProfileStore.multiWake(app), ProfileStore.priority(app))
                if (who == null) { event("wake: $hit (not for the active assistant, ignored)"); return }
                ProfileStore.sessionProfile = who.takeIf { it != ProfileStore.activeId(app) }
                event("wake: $hit → ${prefs.assistantName}")
                startListening()
            }
            Mode.WAKING -> synchronized(wakeBuffer) { if (wakeBuffer.size < 150) wakeBuffer += chunk }
            Mode.LISTEN -> {
                listenAudioMs += chunk.size * 1000L / SAMPLE_RATE
                val s = stt
                if (s == null) {
                    // recognizer went away (unloaded / failed): never sit in "listening" forever
                    if (listenAudioMs > 8000) { event("listen: no speech recognizer, ending"); mode = Mode.BUSY; worker.execute { backToWake() } }
                    return
                }
                val text = try { s.accept(chunk) } catch (e: Throwable) {
                    // never sit in "listening" after a recognizer failure: say so and go back to waiting
                    Log.e(TAG, "stt failed", e); event("listen: speech recognition failed: ${e.message}")
                    mode = Mode.BUSY
                    worker.execute { runCatching { say("Sorry, my speech recognition hit a problem. Please try again.") }; backToWake() }
                    return
                }
                if (s.heardSpeech && !heardLogged) { heardLogged = true; event("listen: speech started at ${listenAudioMs} ms of audio") }
                val elapsed = listenAudioMs
                when {
                    text != null -> onUtterance(text)
                    s.missedSpeech -> {
                        // speech was heard but nothing came out of the recognizer: tell the user instead of waiting forever
                        s.missedSpeech = false
                        event("listen: heard speech but couldn't make out any words (${elapsed / 1000}s of audio)")
                        mode = Mode.BUSY
                        worker.execute { runCatching { say("Sorry, I didn't catch that. Could you say it again?") }; if (!sessionCancelled) startListening(followUp = true, quiet = true) else backToWake() }
                    }
                    ListenPolicy.listenExpired(listenCfg(), elapsed, s.heardSpeech) -> {
                        val rest = s.finish()
                        if (rest != null) onUtterance(rest) else {
                            event("heard: (nothing) — listening timed out after ${elapsed / 1000}s of audio (${(System.currentTimeMillis() - listenStart) / 1000}s wall)")
                            mode = Mode.BUSY
                            worker.execute { if (prefs.stopChime) Sounds.play(app, prefs, "stop"); backToWake() }
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** Push-to-talk: from the avatar, assist gesture, notification, headset button or app. */
    fun listenNow(ctx: Context) {
        init(ctx)
        // only ever called from a user action (avatar/bubble tap, assist gesture, notification, headset, app button),
        // so it counts as a user-visible start: the microphone keeps working afterwards
        if (mode == Mode.OFF || !AssistantService.inForeground) { AssistantService.start(ctx, AssistantService.ACTION_LISTEN, from = "app"); return }
        if (mode == Mode.WAKE || mode == Mode.LISTEN) startListening()
    }

    private fun startListening(followUp: Boolean = false, quiet: Boolean = false) {
        main.removeCallbacks(unloadRunnable)
        if (readerReady && reader.playing) reader.bargePause()
        sessionCancelled = false
        // Context memory lasts while you keep talking; a new session after 5 quiet minutes starts fresh.
        if (!followUp && System.currentTimeMillis() - lastTurnAt > 5 * 60_000L) brain.resetContext()
        val cold = stt == null
        mode = if (cold) Mode.WAKING else Mode.BUSY
        synchronized(wakeBuffer) { wakeBuffer.clear() }
        setUi(if (cold) Ui.WAKING else Ui.LISTENING)
        if (cold) status = "Waking up…"
        // Load the LLM and voice in the background while the user is talking.
        startLlmLoad()
        loader.execute { runCatching { ensureTts() } }
        worker.execute {
            if (prefs.chime && !quiet) Sounds.play(app, prefs, if (followUp) "follow" else "start")
            val s = try { ensureStt() } catch (e: Throwable) {
                event("stt unavailable: ${e.message}")
                status = "Add speech recognition in Settings so I can understand you"
                runCatching { say("I can't understand speech yet. Please add the speech recognition pack in my settings.") }
                backToWake(); return@execute
            }
            s.resetUtterance()
            listenStart = System.currentTimeMillis(); listenAudioMs = 0; heardLogged = false
            feeding = true
            setUi(Ui.LISTENING)
            setMode(Mode.LISTEN, "Listening…")
            // Replay what was said while waking up, in order; new audio waits in the buffer until we catch up.
            var fed = 0
            try {
                while (true) {
                    val next = synchronized(wakeBuffer) { if (wakeBuffer.isEmpty()) { feeding = false; null } else wakeBuffer.removeAt(0) } ?: break
                    if (mode != Mode.LISTEN) { synchronized(wakeBuffer) { wakeBuffer.clear(); feeding = false }; break }
                    onAudio(next, fromFeed = true); fed++
                }
            } finally { synchronized(wakeBuffer) { if (feeding) { wakeBuffer.clear(); feeding = false } } }   // never leave live audio parked
            if (fed > 0) event("fed ${fed * 100} ms of audio captured while waking")
        }
    }

    fun wakeStatus(): String = if (prefs.wakeWordEnabled)
        "Waiting for: " + (wake?.phrases ?: prefs.wakePhrases).joinToString(", ") { "\"" + it.lowercase() + "\"" }
    else "Ready (wake words off; ${Platform.tap} the avatar or Talk)"

    private fun backToWake() {
        ProfileStore.sessionProfile = null   // next wake word decides again
        if (wake == null && prefs.wakeWordEnabled) loader.execute { runCatching { if (wake == null) wake = WakeWordEngine(app, prefs); event("wake: spotter rebuilt after a timeout") } }
        wake?.reset()
        setUi(Ui.IDLE)
        setMode(Mode.WAKE, wakeStatus())
        if (prefs.loadOnDemand) { main.removeCallbacks(unloadRunnable); main.postDelayed(unloadRunnable, prefs.unloadAfterSec * 1000L) }
        // a book paused by the wake word carries on once the conversation is over (incl. listening time-outs)
        if (readerReady && reader.bargePaused) main.postDelayed({ if (mode == Mode.WAKE) reader.resumeAfterBarge() }, 600)
    }

    private fun onUtterance(text: String) {
        Diag.lastHeard = text; Diag.lastHeardAt = System.currentTimeMillis()
        mode = Mode.BUSY
        setUi(Ui.THINKING)
        event("heard: $text")
        worker.execute {
            // Sleep words skip the "got it" chime; they get their own goodbye.
            if (prefs.stopChime && !brain.isSleep(text)) Sounds.play(app, prefs, "stop")
            respond(text, speak = true)
        }
    }

    /**
     * End the conversation now: from a sleep word, the notification's End
     * button, or the avatar menu. Stops speech, drops context, confirms with a
     * short goodbye and/or chime, unloads STT/TTS/LLM when "load on demand" is
     * on, and goes back to listening for wake words only.
     */
    fun endSession(ctx: Context, reason: String, goodbye: String? = null) {
        init(ctx)
        if (mode == Mode.OFF || mode == Mode.LOADING) return
        sessionCancelled = true
        mode = Mode.BUSY
        AudioOut.stop()
        if (readerReady) reader.pause("stopped (conversation ended)")
        brain.resetContext()
        event("session ended ($reason)")
        setUi(Ui.SLEEPING)
        status = "Going to sleep…"
        worker.execute { finishSleep(goodbye ?: "Okay, goodnight.") }
    }

    private fun finishSleep(goodbye: String?) {
        sessionCancelled = false
        val how = prefs.sleepConfirm   // "voice", "chime" or "both"
        var spoke = false
        if (how != "chime" && goodbye != null) spoke = runCatching { say(goodbye, keepUi = true) }.isSuccess
        if (how != "voice" || !spoke) Sounds.play(app, prefs, "sleep")
        main.postDelayed({ if (ui == Ui.SLEEPING) setUi(Ui.IDLE) }, 1500)
        ProfileStore.sessionProfile = null
        wake?.reset()
        setMode(Mode.WAKE, wakeStatus())
        ui = Ui.SLEEPING; notifyListeners()
        if (prefs.loadOnDemand) unloadHeavy(force = true)
    }

    /** Typed command from the app (or adb test hook). */
    fun handleText(ctx: Context, text: String, speak: Boolean = true) {
        init(ctx)
        sessionCancelled = false
        main.removeCallbacks(unloadRunnable)
        startLlmLoad()
        worker.execute { respond(text, speak); if (mode == Mode.WAKE) backToWake() }
    }

    private fun respond(text: String, speak: Boolean) {
        status = "Thinking…"
        setUi(Ui.THINKING)
        busyThinking = true
        main.removeCallbacks(summarizeRunnable)
        llm.preempt()   // a memory summary / warm-up in progress stops now; the reply never waits behind it
        val t0 = System.currentTimeMillis()
        // Long-form replies (stories, songs) are spoken sentence by sentence while the model is still writing.
        bookReply(text)?.let { (reply, after) ->
            busyThinking = false; llm.replyDone()
            addTurn(Turn(true, text)); if (reply.speech.isNotEmpty()) addTurn(Turn(false, reply.speech))
            event("reply[book ${System.currentTimeMillis() - t0}ms]: ${reply.speech.ifEmpty { "(no words: done)" }}")
            if (speak && reply.speech.isNotEmpty()) say(reply.speech)
            after()
            // reading happens while waiting for the wake word, never while listening (so the mic doesn't transcribe the book)
            if (mode == Mode.BUSY || mode == Mode.LISTEN) backToWake() else { setUi(Ui.IDLE); if (reader.bargePaused) reader.resumeAfterBarge() }
            return
        }
        val stream = if (speak) SpeechStream() else null
        val reply = try {
            // Rules first; only wait for a still-loading LLM if the rules can't handle it.
            val quick = RuleParser(prefs.wakePhrases + prefs.assistantName).parse(text)
            val want = gate.pick()
            // Not loaded, or another model was chosen since (settings, other assistant): load the chosen one now.
            if (quick == null && want.ok && (!llm.isReady || (want.file != null && llm.loadedFile != want.file))) {
                // selected but unloaded (on-demand / idle unload / service restart): load it now instead of saying "no model"
                startLlmLoad()
                status = "Loading the thinking model…"
                event("waiting for the thinking model to load…")
                val ok = runCatching { llmFuture?.get(LOAD_WAIT_S, TimeUnit.SECONDS) }.isSuccess
                if (!ok) event("watchdog: the thinking model is still loading after ${LOAD_WAIT_S}s")
                status = "Thinking…"
            }
            llm.preempt()
            when (val r = thinker.run(THINK_LIMIT_MS, onTimeout = { llm.cancel() },
                    progress = { maxOf(brain.lastProgressAt, lastSpokeAt) }, hardCapMs = Brain.LONG_CAP_MS + 120_000L) {
                    brain.handle(text, stream?.let { st -> { sentence: String -> st.offer(sentence) } }) }) {
                is TimedCall.Result.Ok -> r.value
                is TimedCall.Result.Failed -> { Log.e(TAG, "brain failed", r.error); Reply("Sorry, something went wrong.") }
                is TimedCall.Result.TimedOut -> {
                    event("watchdog: no reply after ${THINK_LIMIT_MS / 1000}s, cancelled the model" + if (r.threadReplaced) " (stuck thread replaced)" else "")
                    runCatching { brain.resetContext() }
                    Reply("Sorry, I got stuck thinking about that. Let's try again.")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "brain failed", e); Reply("Sorry, something went wrong.")
        } finally {
            busyThinking = false
            llm.replyDone()
            stream?.finish()
        }
        // Whatever was streamed plays to the end before anything else happens (no listening, no timeout meanwhile).
        if (stream != null && stream.started) { setUi(Ui.SPEAKING); stream.await(); if (mode != Mode.LISTEN) setUi(Ui.IDLE) } else stream?.await()
        val dt = System.currentTimeMillis() - t0
        lastTurnAt = System.currentTimeMillis()
        addTurn(Turn(true, text))
        addTurn(Turn(false, reply.speech, reply.sources, note = reply.note))
        reply.note?.let { event("web: $it") }
        event("reply[${brain.lastSource} ${dt}ms]: ${if (reply.speech.length > 400) reply.speech.take(400) + "… (${reply.speech.length} chars)" else reply.speech} | action=${reply.action?.tool} ${reply.action?.args ?: ""}" +
            (if (brain.lastNote.isNotEmpty()) " | ${brain.lastNote}" else ""))
        if (reply.endSession) { sessionCancelled = true; mode = Mode.BUSY; setUi(Ui.SLEEPING); event("session ended (sleep word)"); finishSleep(reply.speech); return }
        if (sessionCancelled) return   // ended from the notification/avatar while thinking
        // Always talk back.
        if (speak && !reply.streamed) say(WebKnowledge.forSpeech(reply.speech))
        if (sessionCancelled) return
        val inSession = mode == Mode.BUSY || mode == Mode.LISTEN
        when {
            inSession && ListenPolicy.afterReply(listenCfg(), brain.expectsReply) == ListenPolicy.After.LISTEN_AGAIN -> startListening(followUp = true)
            inSession -> backToWake()
            else -> setUi(Ui.IDLE)
        }
    }

    private val synthPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** "Read to me" (any assistant: uses whichever voice and voice speed the active assistant has). */
    val reader: BookReader by lazy {
        BookReader(app, synth = { text, mult ->
            val t = ensureTts()
            val (raw, rate) = t.synth(text, prefs.voiceSpeaker, prefs.voiceSpeed * mult)
            VoiceShift.process(raw, prefs.voiceShift(t.voiceDir.name)) to rate
        }, synthExec = synthPool, log = ::event, changed = ::notifyListeners,
            voiceKey = { "${prefs.voiceDir}|${prefs.voiceSpeaker}|${prefs.voiceSpeed}|${prefs.voiceShift(prefs.voiceDir)}" },
            // reading anywhere (page, widget, notification, voice) runs under the media-playback service: it keeps
            // going in the background and on the lock screen until paused or the sleep timer ends
            onPlayState = { on -> if (on) main.post { BookPlaybackService.ensure(app) } })
    }
    private val readerReady get() = ::app.isInitialized
    private val readerBusy get() = readerReady && (reader.playing || reader.bargePaused)

    /**
     * Spoken book commands ("read me <book>", pause, next chapter, faster, what just happened…). Null = not about
     * the book. They are handled here, word for word, before the rules and the model.
     */
    private fun bookReply(text: String): Pair<Reply, () -> Unit>? {
        val r = reader
        val cmd = BookCommands.parse(text, r.active) ?: return null
        fun say(s: String, after: () -> Unit = {}) = Reply(s) to after
        return when (cmd) {
            is BookCmd.Read -> {
                val e = if (cmd.title == null) r.lib.get(r.lastBookId ?: "") ?: r.lib.list().firstOrNull() else r.lib.find(cmd.title)
                when {
                    e == null && r.lib.list().isEmpty() -> say("Your book library is empty. Open Read to me in the app and import an EPUB, PDF or text file first.")
                    e == null -> say("I couldn't find a book called ${cmd.title}. You have ${r.lib.list().joinToString(", ") { it.title }}.")
                    else -> { r.open(e.id); val p = r.lib.position(e.id)
                        say(if (p != null && !p.finished && (p.chapter != e.readable.firstOrNull() || p.sentence > 0)) "Picking up ${e.title} in ${r.chapterTitle}." else "Starting ${e.title}.") { r.play() } }
                }
            }
            BookCmd.Pause -> { r.pause(); say("") }
            BookCmd.Stop -> { r.stop(); BookPlaybackService.dismiss(app); freeVoiceIfIdle("book stopped"); say("") }
            is BookCmd.Unload -> if (cmd.everything) say("Unloading everything. Your books and bookmarks are kept.") { unloadEverything(app) }
                else { r.unload(); BookPlaybackService.dismiss(app); say("Book unloaded. Your place is saved.") { freeVoiceIfIdle("book unloaded") } }
            BookCmd.Resume -> say("") { if (r.book == null) r.open(); r.play() }
            BookCmd.NextChapter -> if (r.nextChapter()) say("") else say("That's the last chapter.")
            BookCmd.PrevChapter -> { r.prevChapter(); say("") }
            BookCmd.RestartChapter -> { r.restartChapter(); say("") }
            is BookCmd.Back -> { r.back(cmd.sentences); say("") }
            BookCmd.Faster -> { r.faster(); say("") }
            BookCmd.Slower -> { r.slower(); say("") }
            is BookCmd.GoTo -> if (r.goTo(cmd.chapter)) say("") else say("This book doesn't have a chapter ${cmd.chapter}.")
            is BookCmd.Sleep -> { r.setSleep(cmd.minutes); say(if (cmd.minutes <= 0) "Sleep timer off." else "I'll stop reading in ${cmd.minutes} minutes.") }
            BookCmd.WhereAmI -> say(r.whereAmI())
            BookCmd.Recap -> say(recap())
        }
    }

    /** (book · chapter, playing) for the notification and widget; null when no book is open. */
    /** Frees the voice (it reloads on the next sentence or reply) unless a conversation is using it right now. */
    private fun freeVoiceIfIdle(why: String) {
        if (inSession || speakingNow) return
        // on the reader's voice thread, after any sentence still being made (never frees the voice under a synth)
        synthPool.execute { synchronized(this) { if (tts != null) { tts?.release(); tts = null; event("$why: voice unloaded") } } }
    }
    private fun pssMb(): Long = android.os.Debug.getPss() / 1024

    /**
     * "Unload everything": the open book (its read-ahead audio and the cache file of it), the voice, speech-to-text and
     * the language model leave memory. No file, book, bookmark or setting is deleted; each part loads again when it
     * is next needed (the wake word keeps listening if the assistant is on).
     */
    fun unloadEverything(ctx: Context) {
        init(ctx)
        val before = pssMb()
        event("unload: everything (process PSS before ${before} MB)")
        if (readerReady) reader.unload()
        BookPlaybackService.dismiss(app)
        main.removeCallbacks(unloadRunnable)
        loader.execute {
            runCatching { synthPool.submit { synchronized(this) { tts?.release(); tts = null } }.get(60, TimeUnit.SECONDS) }
            synchronized(this) { stt?.release(); stt = null }
            llmFuture?.let { runCatching { it.get(30, TimeUnit.SECONDS) } }
            runCatching { llmLoader.submit { gate.unload() }.get(60, TimeUnit.SECONDS) }
            System.gc(); Runtime.getRuntime().gc()
            Thread.sleep(500)
            event("unload: book, voice, speech-to-text and language model unloaded; process PSS ${before} MB → ${pssMb()} MB")
            notifyListeners(); main.post { AssistantWidget.onStateChanged(app) }
        }
    }

    /** Any book in the library (without opening the reader). */
    fun hasBooks(ctx: Context): Boolean = File(ctx.filesDir, "books").listFiles()?.any { it.isDirectory && !it.name.startsWith("import-") } == true
    fun bookNow(): Pair<String, Boolean>? = if (!readerReady) null else reader.nowLine()?.let { it to (reader.playing || reader.bargePaused) }

    /** Buttons (notification, widget, Read to me page) and the debug test hook. */
    fun bookControl(ctx: Context, cmd: String, arg: String? = null) {
        init(ctx)
        val r = reader
        event("book: control $cmd${arg?.let { " $it" } ?: ""}")
        when (cmd) {
            "toggle" -> if (r.playing || r.bargePaused) r.pause() else { if (r.book == null) r.open(); r.play() }
            "play" -> { if (arg != null) r.open(arg) else if (r.book == null) r.open(); r.play() }
            "pause" -> r.pause()
            "stop" -> { r.stop(); BookPlaybackService.dismiss(ctx); freeVoiceIfIdle("book stopped"); AssistantWidget.onStateChanged(app) }
            "unload" -> { r.unload(); BookPlaybackService.dismiss(ctx); freeVoiceIfIdle("book unloaded"); AssistantWidget.onStateChanged(app) }
            "unloadAll" -> unloadEverything(ctx)
            "close" -> r.close()
            "next" -> r.nextChapter()
            "prev" -> r.prevChapter()
            "back" -> r.back(arg?.toIntOrNull() ?: 3)
            "faster" -> r.faster()
            "slower" -> r.slower()
            "speed" -> arg?.toFloatOrNull()?.let { r.speed = it }
            "chapter" -> arg?.toIntOrNull()?.let { if (r.book == null) r.open(); r.goToChapterIndex(it) }
            "sleep" -> r.setSleep(arg?.toIntOrNull() ?: 0)
            "delete" -> arg?.let { r.delete(it) }
            "status" -> event("book: status ${r.nowLine()} sentence ${r.sentence} playing=${r.playing} barge=${r.bargePaused} speed=${r.speed} played=${r.sentencesPlayed} peakPss=${r.peakPlayPssKb / 1024}MB")
        }
    }

    @Volatile var importing: String? = null; private set
    /** Imports a book on its own thread (file picker → [uri], or a file path from the debug hook). */
    fun importBook(ctx: Context, src: Any, done: (String) -> Unit = {}) {
        init(ctx)
        Thread({
            importing = "Opening the book…"; notifyListeners()
            val msg = try {
                val res = when (src) {
                    is android.net.Uri -> BookImport.import(app, reader.lib, src) { importing = it; notifyListeners() }
                    is File -> BookImport.importFile(app, reader.lib, src, src.name, { importing = it; notifyListeners() })
                    else -> error("unknown source")
                }
                val e = res.entry
                event("book: imported \"${e.title}\" (${e.format}) in ${res.ms} ms: ${e.readable.size} chapters, ${e.words} words; ${res.mem}")
                event("book: chapters " + e.chapters.joinToString(" | ") { "${it.kind.name.lowercase()}:${it.title}(${it.words}w)" })
                "Added ${e.title}: ${e.readable.size} chapters."
            } catch (e: Throwable) {
                Log.w(TAG, "book import failed", e); event("book: import failed: ${e.message}")
                e.message ?: "Couldn't import that book."
            } finally { importing = null; notifyListeners() }
            main.post { done(msg) }
        }, "belle-import").apply { isDaemon = true; start() }
    }

    /** "What just happened?": the model sees only the last ~1,500 tokens read (never the whole book). */
    private fun recap(): String {
        val recent = reader.recentText(6000)
        if (recent.isBlank()) return "We haven't read anything yet."
        startLlmLoad()
        if (!llm.isReady) runCatching { llmFuture?.get(LOAD_WAIT_S, TimeUnit.SECONDS) }
        if (!llm.isReady) return "I need a thinking model to sum that up. You're in ${reader.chapterTitle}."
        val sys = "You help someone listening to an audiobook. In 2 to 4 short spoken sentences, say plainly what just happened in the passage, " +
            "who was involved and why it matters. Only use what the passage says. No lists, no spoilers beyond it."
        val out = runCatching { llm.complete(sys, "Book: ${reader.book?.title}, ${reader.chapterTitle}.\n\nPassage just read:\n$recent", "", 220) }.getOrDefault("").trim()
        event("book: recap from ${recent.length} chars of recent text")
        return out.ifBlank { "Sorry, I couldn't sum that up." }
    }

    /** Speaks [text] sentence by sentence: the next sentence is synthesized while the current one plays, so slower
     *  voices (Kokoro, "high", full precision) start talking after the first sentence instead of the whole reply. */
    fun say(text: String, keepUi: Boolean = false) {
        val t = try { ensureTts() } catch (e: Throwable) { event("tts failed: ${e.message}"); return }
        val shift = prefs.voiceShift(t.voiceDir.name)
        val parts = splitForSpeech(text)
        val t0 = System.currentTimeMillis(); val stops = AudioOut.stops
        val jobs = parts.map { part -> synthPool.submit<Pair<FloatArray, Int>> { val (raw, rate) = t.synth(part, prefs.voiceSpeaker, prefs.voiceSpeed); VoiceShift.process(raw, shift) to rate } }
        if (!keepUi) setUi(Ui.SPEAKING)
        speakingNow = true
        try {
            for ((i, j) in jobs.withIndex()) {
                val (samples, rate) = try { j.get() } catch (e: Exception) { event("tts failed: ${e.cause?.message ?: e.message}"); continue }
                if (i == 0) event("tts: first of ${jobs.size} part(s) ready in ${System.currentTimeMillis() - t0} ms @${rate}Hz (voice shift ${"%.2f".format(shift)})")
                if (AudioOut.stops != stops) { jobs.forEach { it.cancel(false) }; break }
                lastSpokeAt = System.currentTimeMillis()
                AudioOut.play(samples, rate)
                lastSpokeAt = System.currentTimeMillis()
            }
        } finally { speakingNow = false }
        if (!keepUi && mode != Mode.LISTEN) setUi(Ui.IDLE)
    }

    /**
     * Plays sentences as they arrive from a streamed reply: each is synthesized in order on [synthPool] (the next one
     * while the current one plays) and played back to back. A stop (tap, sleep word, reset) ends it, and [offer] then
     * returns false so the model stops writing too.
     */
    private class SpeechStream {
        private val eof = java.util.concurrent.CompletableFuture.completedFuture<Pair<FloatArray, Int>?>(null)
        private val q = java.util.concurrent.LinkedBlockingQueue<java.util.concurrent.Future<Pair<FloatArray, Int>?>>()
        private val stops0 = AudioOut.stops
        private val t0 = System.currentTimeMillis()
        @Volatile var stopped = false; private set
        @Volatile var started = false; private set
        private var count = 0
        private val player = Thread({
            while (true) {
                val f = q.take()
                if (f === eof) break
                val r = try { f.get() } catch (e: Exception) { event("tts failed: ${e.cause?.message ?: e.message}"); null }
                if (stopped || AudioOut.stops != stops0 || sessionCancelled) { stopped = true; q.forEach { it.cancel(false) }; q.clear(); break }
                if (r == null) continue
                if (!started) { started = true; speakingNow = true; setUi(Ui.SPEAKING); event("tts: streaming, first sentence playing ${System.currentTimeMillis() - t0} ms after the question") }
                lastSpokeAt = System.currentTimeMillis()
                AudioOut.play(r.first, r.second)
                lastSpokeAt = System.currentTimeMillis()
            }
            speakingNow = false
        }, "belle-speak").apply { isDaemon = true; start() }

        /** Queue one sentence; false = the user stopped the voice (the model should stop writing). */
        fun offer(sentence: String): Boolean {
            if (stopped || AudioOut.stops != stops0 || sessionCancelled) { stopped = true; return false }
            val t = try { ensureTts() } catch (e: Throwable) { event("tts failed: ${e.message}"); return true }
            count++
            q.put(synthPool.submit<Pair<FloatArray, Int>?> {
                if (stopped) null else { val (raw, rate) = t.synth(sentence, prefs.voiceSpeaker, prefs.voiceSpeed); VoiceShift.process(raw, prefs.voiceShift(t.voiceDir.name)) to rate }
            })
            return true
        }
        fun finish() { q.put(eof) }
        fun await() { player.join(); if (count > 0) event("tts: streamed $count sentence(s)${if (stopped) " (stopped)" else ""}") }
    }

    fun previewVoice(ctx: Context, voiceDir: String, text: String, speaker: Int? = null) {
        init(ctx)
        worker.execute {
            try {
                ModelFiles.ensureBundled(app)
                val v = TextToSpeech(app, voiceDir, ttsQuality())
                val (raw, r) = v.synth(text, speaker ?: prefs.speakerFor(voiceDir), prefs.voiceSpeed)
                val s = VoiceShift.process(raw, prefs.voiceShift(voiceDir))
                event("preview $voiceDir: ${s.size} samples")
                setUi(Ui.SPEAKING); AudioOut.play(s, r); setUi(Ui.IDLE); v.release()
            } catch (e: Throwable) { event("preview failed: ${e.message}") }
        }
    }

    fun stop() {
        AudioOut.stop()
        if (readerReady) reader.pause("stopped (assistant turned off)")
        main.removeCallbacks(unloadRunnable)
        setUi(Ui.IDLE)
        setMode(Mode.OFF, "Stopped")
        loader.execute { stt?.release(); stt = null; tts?.release(); tts = null; runCatching { llmLoader.submit { gate.unload() }.get(30, TimeUnit.SECONDS) }; wake?.release(); wake = null }
    }

    /**
     * Debug-only test hook: feed a 16 kHz mono WAV through the exact same
     * wake → listen → STT → brain → action → TTS path as the microphone.
     */
    fun injectWav(path: String) {
        Thread {
            val samples = readWav16k(File(path)) ?: run { event("inject: cannot read $path"); return@Thread }
            event("inject: ${samples.size / SAMPLE_RATE.toFloat()} s from $path")
            injecting = true
            try {
                var i = 0
                while (i < samples.size) {
                    val n = minOf(1600, samples.size - i)
                    onAudio(samples.copyOfRange(i, i + n), fromInjection = true)
                    i += n
                    Thread.sleep(100)   // real time, like a microphone
                    while (mode == Mode.BUSY) Thread.sleep(50)
                }
                repeat(40) { onAudio(FloatArray(1600), fromInjection = true); Thread.sleep(100); while (mode == Mode.BUSY) Thread.sleep(50) }
            } finally { injecting = false }
            event("inject: done")
        }.start()
    }

    private fun readWav16k(f: File): FloatArray? {
        if (!f.exists()) return null
        RandomAccessFile(f, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt()); raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12; var fmt = 1; var bits = 16; var rate = 16000; var ch = 1
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4); val size = bb.getInt(pos + 4)
                if (id == "fmt ") { fmt = bb.getShort(pos + 8).toInt(); ch = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
                if (id == "data") {
                    val start = pos + 8
                    val n = minOf(size, bytes.size - start) / (bits / 8) / ch
                    val out = FloatArray(n) { i ->
                        val o = start + i * ch * (bits / 8)
                        if (fmt == 3 && bits == 32) bb.getFloat(o) else bb.getShort(o) / 32768f
                    }
                    if (rate != SAMPLE_RATE) { event("inject: wav must be 16 kHz (got $rate)"); return null }
                    return out
                }
                pos += 8 + size + (size and 1)
            }
        }
        return null
    }
}

/** Sentence-sized chunks (keeps very short sentences together so the voice doesn't sound choppy). */
internal fun splitForSpeech(text: String): List<String> {
    val raw = text.trim().split(Regex("(?<=[.!?…])\\s+(?=[A-Z0-9\"'(])|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    val out = ArrayList<String>()
    for (r in raw) if (out.isNotEmpty() && (out.last().length < 40)) out[out.size - 1] = out.last() + " " + r else out += r
    return out.ifEmpty { listOf(text) }
}

