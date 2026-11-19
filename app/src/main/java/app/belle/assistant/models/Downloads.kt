package app.belle.assistant.models

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.core.ModelCatalog
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One thing on the "Get models" screen. kind: llm | stt | voice | 3d */
data class Dl(val id: String, val kind: String, val label: String, val url: String, val fileName: String, val bytes: Long, val sha256: String,
              val minRamGb: Int = 0, val license: String = "", val note: String = "", val parts: List<Dl> = emptyList())

/**
 * One-tap downloads inside the app.
 *  - Only official Hugging Face / GitHub URLs (redirects only to their CDNs).
 *  - Resumable: keeps a .part file and continues with an HTTP Range request.
 *  - SHA-256 verified before the file is used; a bad file is deleted.
 *  - This is the ONLY network use besides the opt-in "Internet access" switch, and it starts only after
 *    the user confirms a download.
 */
object Downloads {
    enum class Phase { IDLE, QUEUED, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED, PAUSED }
    data class St(val phase: Phase = Phase.IDLE, val done: Long = 0, val total: Long = 0, val msg: String = "")

    private val ALLOWED_START = setOf("huggingface.co", "github.com")
    private val ALLOWED_SUFFIX = listOf(".huggingface.co", ".hf.co", ".githubusercontent.com", ".xethub.hf.co")

    fun hostAllowed(host: String, first: Boolean): Boolean {
        val h = host.lowercase()
        if (h in ALLOWED_START) return true
        return !first && (ALLOWED_SUFFIX.any { h.endsWith(it) } || h == "hf.co")
    }

    val state = ConcurrentHashMap<String, St>()
    @Volatile private var etaObj: app.belle.assistant.core.TransferEta? = null
    /** One smoothed speed for everything downloading (the one-tap models and the 3D generation set share the connection). */
    fun eta(ctx: Context): app.belle.assistant.core.TransferEta = etaObj ?: synchronized(this) {
        etaObj ?: app.belle.assistant.core.TransferEta(ctx.getSharedPreferences("downloads_eta", Context.MODE_PRIVATE)
            .getFloat("bytes_per_sec", 0f).toDouble().takeIf { it > 0 }).also { etaObj = it }
    }
    private val cancel = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private var lastPost = 0L
    private fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis(); if (!force && now - lastPost < 250) return; lastPost = now
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
    }

    /** Lets another downloader (the 3D installer) show up in the shared progress notification. null = remove. */
    fun external(id: String, st: St?) { if (st == null) state.remove(id) else state[id] = st; changed(st == null || st.phase != Phase.DOWNLOADING) }

    fun catalog(): List<Dl> {
        val c = ModelCatalog
        val speech = Dl("speech", "stt", "Speech recognition (Moonshine Tiny + Silero VAD)", "", "", c.STT.bytes + c.VAD.bytes, "", license = "MIT",
            note = "Required so I can understand your voice.", parts = listOf(
                Dl("vad", "vad", c.VAD.label, c.VAD.url, c.VAD.fileName, c.VAD.bytes, c.VAD.sha256),
                Dl("stt", "stt", c.STT.label, c.STT.url, c.STT.fileName, c.STT.bytes, c.STT.sha256)))
        val llms = c.LLMS.map { Dl(it.id, "llm", it.label, it.url, it.fileName, it.bytes, it.sha256, it.minRamGb, it.license, it.note) }
        val voices = c.VOICES.map { Dl("voice:" + it.dirName, "voice", it.label, it.archiveUrl, it.dirName + ".tar.bz2", it.approxBytes, it.sha256,
            license = it.license, note = "${it.gender}, ${it.accent}") }
        // 3D generation (Pixal3D ONNX set) has its own installer: threed/Pixal3DInstall (Settings › 3D avatar).
        return listOf(speech) + llms + voices 
    }

    // total RAM never changes: one system call per process (it is a binder call, not free on the main thread)
    @Volatile private var ramGbCache = 0.0
    fun totalRamGb(ctx: Context): Double = ramGbCache.takeIf { it > 0 }
        ?: (ActivityManager.MemoryInfo().also { (ctx.getSystemService(ActivityManager::class.java)).getMemoryInfo(it) }.totalMem / 1e9).also { ramGbCache = it }

    /** "Fits", "Tight" or "Too big" for this phone. */
    fun ramFit(ctx: Context, d: Dl): Pair<String, Int> = ramFit(totalRamGb(ctx), d)
    fun ramFit(ram: Double, d: Dl): Pair<String, Int> {
        if (d.minRamGb == 0) return "Fits" to 0
        return when {
            d.kind == "3d" && d.minRamGb >= 12 -> (if (ram >= 11) "Experimental on your ${"%.0f".format(ram)} GB phone (runs stage by stage)" to 1 else "Needs a 12 GB phone; use the PC import" to 2)
            ram >= d.minRamGb * 0.92 -> "Fits your ${"%.0f".format(ram)} GB phone" to 0
            ram >= d.minRamGb * 0.7 -> "Tight on ${"%.0f".format(ram)} GB" to 1
            else -> "Too big for ${"%.0f".format(ram)} GB" to 2
        }
    }

    fun installed(ctx: Context, d: Dl): Boolean = when (d.kind) {
        "stt" -> ModelFiles.hasStt(ctx)
        "llm" -> File(ModelFiles.llmDir(ctx), d.fileName).let { it.exists() && it.length() == d.bytes }
        "voice" -> File(ModelFiles.voicesDir(ctx), d.id.removePrefix("voice:")).isDirectory
        "3d" -> d.parts.all { File(threeDDir(ctx), it.fileName).length() == it.bytes }
        else -> false
    }

    fun threeDDir(ctx: Context) = File(ModelFiles.root(ctx), "3d").apply { mkdirs() }
    private fun dlDir(ctx: Context) = File(ctx.filesDir, "dl").apply { mkdirs() }

    fun cancel(id: String) { cancel += id }

    /** Automatic retries per download before it shows as failed (with its Download/Retry button). */
    const val AUTO_RETRIES = 4
    /** Back-off before retry [attempt] (0-based): 2 s, 4 s, 8 s, 16 s … capped at 60 s. */
    fun retryDelayMs(attempt: Int): Long = minOf(2000L shl attempt.coerceIn(0, 10), 60_000L)

    // Downloads the user started and that haven't finished: kept in prefs so they resume after the app is killed or the phone restarts.
    private fun wantedPrefs(ctx: Context) = ctx.getSharedPreferences("downloads_wanted", Context.MODE_PRIVATE)
    private fun setWanted(ctx: Context, id: String, on: Boolean) = synchronized(this) {
        val cur = wantedPrefs(ctx).getStringSet("ids", emptySet())!!.toMutableSet()
        if (if (on) cur.add(id) else cur.remove(id)) wantedPrefs(ctx).edit().putStringSet("ids", cur).apply()
    }
    fun wantedIds(ctx: Context): Set<String> = wantedPrefs(ctx).getStringSet("ids", emptySet())!!.toSet()
    /** Restart every unfinished download the user started. Call where a foreground service may start. Returns how many resumed. */
    fun resumeWanted(ctx: Context): Int {
        val ids = wantedIds(ctx); if (ids.isEmpty()) return 0
        var n = 0
        for (d in catalog()) if (d.id in ids) {
            if (installed(ctx, d)) setWanted(ctx, d.id, false) else { start(ctx, d); n++ }
        }
        return n
    }

    fun start(ctx: Context, d: Dl) {
        val app = ctx.applicationContext
        if (state[d.id]?.phase.let { it == Phase.QUEUED || it == Phase.DOWNLOADING || it == Phase.VERIFYING || it == Phase.INSTALLING }) return
        cancel -= d.id
        state[d.id] = St(Phase.QUEUED, 0, d.bytes); changed(true)
        DownloadService.started(app)
        setWanted(app, d.id, true)
        pool.execute {
            var attempt = 0
            var r: Result<Unit>
            while (true) {
                r = runCatching {
                    val parts = d.parts.ifEmpty { listOf(d) }
                    var base = 0L
                    for (p in parts) {
                        if (p.kind == "stt" && ModelFiles.hasStt(app) && parts.size > 1) { base += p.bytes; continue }
                        val dest = when (p.kind) { "llm" -> File(ModelFiles.llmDir(app), p.fileName); "3d" -> File(threeDDir(app), p.fileName); else -> File(dlDir(app), p.fileName) }
                        if (!(dest.exists() && dest.length() == p.bytes)) fetch(d.id, p, dest, base, d.bytes)
                        state[d.id] = St(Phase.INSTALLING, base + p.bytes, d.bytes, "Installing…"); changed(true)
                        install(app, p, dest)
                        base += p.bytes
                    }
                }
                // Transient failures (dropped connection, timeout, server hiccup) retry on their own, resuming the .part file.
                if (r.isSuccess || d.id in cancel || attempt >= AUTO_RETRIES) break
                val wait = retryDelayMs(attempt++)
                state[d.id] = St(Phase.DOWNLOADING, state[d.id]?.done ?: 0, d.bytes, "${r.exceptionOrNull()?.message ?: "Error"} — trying again ($attempt/$AUTO_RETRIES)"); changed(true)
                val until = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < until && d.id !in cancel) Thread.sleep(100)
            }
            if (r.isSuccess || d.id in cancel) setWanted(app, d.id, false)
            state[d.id] = r.fold({ St(Phase.DONE, d.bytes, d.bytes, "Installed") },
                { e -> if (d.id in cancel) St(Phase.PAUSED, state[d.id]?.done ?: 0, d.bytes, "Paused — tap Download to resume") else St(Phase.FAILED, state[d.id]?.done ?: 0, d.bytes, e.message ?: "Failed") })
            changed(true)
            DownloadService.finished(app)
        }
    }

    private fun fetch(id: String, p: Dl, dest: File, base: Long, total: Long) {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > p.bytes) { part.delete(); have = 0 }
        var u = URL(p.url); var hops = 0
        require(u.protocol == "https" && hostAllowed(u.host, first = true)) { "Blocked: not an official Hugging Face/GitHub URL" }
        var conn: HttpURLConnection
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "MilkyMoosAssistant/1.0")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without location"); conn.disconnect()
                u = URL(u, loc)
                require(u.protocol == "https" && hostAllowed(u.host, first = false)) { "Blocked redirect to ${u.host}" }
                if (++hops > 8) error("Too many redirects"); continue
            }
            if (code == 416 && have == p.bytes) { conn.disconnect(); break }
            if (code != 200 && code != 206) error("HTTP $code")
            if (code == 200) have = 0  // server ignored Range: start over
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(have); raf.seek(have)
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (id in cancel) error("cancelled")
                        val n = inp.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); have += n
                        state[id] = St(Phase.DOWNLOADING, base + have, total, ""); changed()
                    }
                }
            }
            conn.disconnect(); break
        }
        if (part.length() != p.bytes) error("Download incomplete (${part.length()} of ${p.bytes} bytes) — tap Download to resume")
        state[id] = St(Phase.VERIFYING, base + have, total, "Checking SHA-256…"); changed(true)
        if (p.sha256.isNotEmpty()) {
            val sha = sha256(part)
            if (!sha.equals(p.sha256, true)) { part.delete(); error("Checksum mismatch — the file was damaged and deleted. Try again.") }
        }
        check(part.renameTo(dest)) { "Couldn't save ${dest.name}" }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(ctx: Context, p: Dl, f: File) {
        val prefs = Prefs(ctx, ProfileStore.activeId(ctx))
        when (p.kind) {
            "llm" -> if (prefs.llmFile.isEmpty() || !File(ModelFiles.llmDir(ctx), prefs.llmFile).exists()) prefs.llmFile = f.name
            "vad", "stt", "voice" -> {
                val r = ModelImport.importAny(ctx, Uri.fromFile(f), f.name).getOrThrow()
                f.delete()
                if (p.kind == "voice") {
                    val dir = r.removePrefix("voice:")
                    // First real voice, or the recommended high-quality one while still on the small built-in default: use it.
                    val rec = ModelCatalog.recommendedVoice(run { val mi = android.app.ActivityManager.MemoryInfo(); ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); mi.totalMem / 1073741824.0 })
                    if (!File(ModelFiles.voicesDir(ctx), prefs.voiceDir).isDirectory || (prefs.voiceDir == ModelFiles.DEFAULT_VOICE && dir == rec?.dirName)) prefs.voiceDir = dir
                }
            }
            "3d" -> {}
        }
    }
}
