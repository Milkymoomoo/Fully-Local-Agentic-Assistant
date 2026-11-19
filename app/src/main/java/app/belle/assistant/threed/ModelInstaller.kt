package app.belle.assistant.threed

import app.belle.assistant.models.Fetcher
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The "Install 3D generation" download set (assets/pixal3d_models.json, made by tools/publish/make_release_manifest.py):
 * every file the on-phone Pixal3D run needs, with its size, SHA-256, licence and where it comes from — GitHub Release
 * assets of the public repo (our ONNX conversions) or the original publisher. A file bigger than one release asset may
 * be split into [Part]s that are joined after download and checked as a whole.
 */
class ModelManifest(val version: String, val release: String, val files: List<Entry>, val licenses: List<License>) {
    class Part(val asset: String, val url: String, val size: Long, val sha256: String)
    class Entry(val name: String, val label: String, val size: Long, val sha256: String, val license: String, val url: String, val parts: List<Part>)
    /** A licence the user must see (and, if [mustAccept], accept) before the files in [files] are downloaded. */
    class License(val id: String, val title: String, val url: String, val notice: String, val mustAccept: Boolean, val files: List<String>)
    val totalBytes get() = files.sumOf { it.size }

    companion object {
        fun parse(json: String): ModelManifest {
            val j = JSONObject(json)
            val files = j.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { f ->
                val parts = f.optJSONArray("parts")?.let { p -> (0 until p.length()).map { p.getJSONObject(it) }.map { Part(it.getString("asset"), it.getString("url"), it.getLong("size"), it.getString("sha256")) } } ?: emptyList()
                Entry(f.getString("name"), f.optString("label", f.getString("name")), f.getLong("size"), f.getString("sha256"), f.optString("license"), f.optString("url"), parts)
            }
            val lic = j.optJSONArray("licenses")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.map { l ->
                License(l.getString("id"), l.getString("title"), l.optString("url"), l.optString("notice"), l.optBoolean("mustAccept"),
                    l.optJSONArray("files")?.let { f -> (0 until f.length()).map { f.getString(it) } } ?: emptyList())
            } ?: emptyList()
            for (f in files) {
                require(!f.name.contains('/') && !f.name.startsWith(".")) { "bad file name ${f.name}" }
                require(f.url.isNotEmpty() || f.parts.isNotEmpty()) { "${f.name}: no source" }
                if (f.parts.isNotEmpty()) require(f.parts.sumOf { it.size } == f.size) { "${f.name}: parts don't add up" }
            }
            return ModelManifest(j.optString("version"), j.optString("release"), files, lic)
        }
    }
}

/**
 * Downloads a [ModelManifest] into [dir] file by file: per-file status and progress, resume after a drop or a restart
 * (Range requests on `.part` files), SHA-256 check of every file, automatic retries with back-off, a retry button per
 * file, retry-all and cancel. Pure JVM (tested against a local HTTP server); the app wraps it in [Pixal3DInstall].
 *
 * Files are fetched one after another, in manifest order (a single worker), so one button installs everything without
 * two big files competing for the connection. Each file is retried automatically ([autoRetries], back-off 2 s, 4 s,
 * 8 s … capped at 60 s) before it shows as FAILED with its own Retry button.
 *
 * "Wanted" marker: [installAll] / [retry] / [retryAll] write `.install_wanted` in [dir]; it is removed when everything is
 * installed or the user presses Cancel. A new installer (after the app was killed or the phone restarted) can then
 * [resumeIfWanted] and carry on from the `.part` files.
 */
class ModelInstaller(val dir: File, val manifest: ModelManifest, private val fetcher: Fetcher = Fetcher(),
                     private val pool: ExecutorService = Executors.newSingleThreadExecutor(), private val autoRetries: Int = 5,
                     private val backoffMs: (Int) -> Long = { minOf(2000L shl it, 60_000L) }) {
    enum class Phase { WAITING, QUEUED, DOWNLOADING, JOINING, VERIFYING, DONE, FAILED, CANCELLED }
    data class St(val phase: Phase, val done: Long, val total: Long, val msg: String = "")

    val status = ConcurrentHashMap<String, St>()
    @Volatile private var cancelAll = false
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    init { refresh() }

    /** Marks files that are already installed (size + recorded checksum, as written by this installer or the folder import). */
    fun refresh() {
        for (f in manifest.files) if (f.name !in running) status[f.name] = if (installed(f)) St(Phase.DONE, f.size, f.size, "Installed") else
            status[f.name]?.takeIf { it.phase == Phase.FAILED || it.phase == Phase.CANCELLED } ?: St(Phase.WAITING, File(dir, f.name + ".part").length(), f.size)
    }
    fun installed(f: ModelManifest.Entry): Boolean { val d = File(dir, f.name)
        return d.length() == f.size && File(dir, f.name + ".sha256").takeIf { it.exists() }?.readText()?.trim().equals(f.sha256, true) }
    val allDone get() = manifest.files.all { status[it.name]?.phase == Phase.DONE }
    val busy get() = running.isNotEmpty()
    val bytesDone get() = manifest.files.sumOf { status[it.name]?.done ?: 0L }
    fun remainingBytes() = manifest.files.filter { !installed(it) }.sumOf { it.size - File(dir, it.name + ".part").length().coerceAtMost(it.size) }

    private val wantedFile get() = File(dir, ".install_wanted")
    /** True while the user has asked for the install and it isn't finished (survives app restarts and reboots). */
    val wanted: Boolean get() = wantedFile.exists() && !manifest.files.all { installed(it) }
    private fun want(on: Boolean) { runCatching { if (on) { dir.mkdirs(); wantedFile.writeText("1") } else wantedFile.delete() } }

    /** Starts (or resumes) everything that isn't installed yet. */
    fun installAll() { cancelAll = false; want(true); manifest.files.forEach { if (status[it.name]?.phase != Phase.DONE) start(it) } }
    /** Retry every failed or cancelled file. */
    fun retryAll() { cancelAll = false; want(true); manifest.files.forEach { val p = status[it.name]?.phase; if (p == Phase.FAILED || p == Phase.CANCELLED || p == Phase.WAITING) start(it) } }
    fun retry(name: String) { cancelAll = false; want(true); manifest.files.firstOrNull { it.name == name }?.let(::start) }
    /** Stops all downloads; what has arrived is kept for a later Install / Retry, but nothing resumes by itself. */
    fun cancel() { cancelAll = true; want(false) }
    /** After a restart: continue an install the user started and didn't cancel. Returns true if it resumed. */
    fun resumeIfWanted(): Boolean {
        if (!wantedFile.exists()) return false
        if (manifest.files.all { installed(it) }) { want(false); return false }
        if (busy) return true
        installAll(); return true
    }
    /** Files installed and checksum-marked, out of [ModelManifest.files]. */
    fun readyCount(): Int = manifest.files.count { installed(it) }

    private fun start(f: ModelManifest.Entry) {
        if (!running.add(f.name)) return
        status[f.name] = St(Phase.QUEUED, status[f.name]?.done ?: 0, f.size, "Waiting…"); changed()
        pool.execute {
            var attempt = 0
            while (true) {
                val r = runCatching { get(f) }
                val e = r.exceptionOrNull()
                if (e == null) { status[f.name] = St(Phase.DONE, f.size, f.size, "Installed"); break }
                if (e is Fetcher.Cancelled || cancelAll) { status[f.name] = St(Phase.CANCELLED, done(f), f.size, "Stopped — retry to continue"); break }
                if (attempt >= autoRetries) { status[f.name] = St(Phase.FAILED, done(f), f.size, e.message ?: e.toString()); break }
                status[f.name] = St(Phase.DOWNLOADING, done(f), f.size, "${e.message ?: "Error"} — trying again (${attempt + 1}/$autoRetries)"); changed()
                val until = System.currentTimeMillis() + backoffMs(attempt++)
                while (System.currentTimeMillis() < until && !cancelAll) Thread.sleep(50)
            }
            running -= f.name
            if (running.isEmpty() && manifest.files.all { installed(it) }) want(false)
            changed()
        }
    }

    private fun done(f: ModelManifest.Entry): Long = if (installed(f)) f.size else if (f.parts.isEmpty()) File(dir, f.name + ".part").length()
        else f.parts.sumOf { p -> File(partsDir(), p.asset).let { if (it.exists()) it.length() else File(it.path + ".part").length() } }
    private fun partsDir() = File(dir, ".parts").apply { mkdirs() }

    private fun get(f: ModelManifest.Entry) {
        if (installed(f)) return
        val dest = File(dir, f.name); dir.mkdirs()
        val cancelled = { cancelAll }
        var last = 0L
        fun report(bytes: Long) { val now = System.nanoTime(); if (now - last > 200_000_000L || bytes == f.size) { last = now; status[f.name] = St(Phase.DOWNLOADING, bytes, f.size); changed() } }
        if (f.parts.isEmpty()) {
            fetcher.fetch(f.url, dest, f.size, "", ::report, cancelled)   // whole-file hash below (also covers an old copy)
        } else {
            var base = 0L
            for (p in f.parts) { val b = base; fetcher.fetch(p.url, File(partsDir(), p.asset), p.size, p.sha256, { report(b + it) }, cancelled); base += p.size }
            status[f.name] = St(Phase.JOINING, f.size, f.size, "Joining ${f.parts.size} parts…"); changed()
            val tmp = File(dir, f.name + ".join")
            tmp.outputStream().use { o -> for (p in f.parts) File(partsDir(), p.asset).inputStream().use { it.copyTo(o, 1 shl 20) } }
            dest.delete(); check(tmp.renameTo(dest)) { "couldn't save ${f.name}" }
            f.parts.forEach { File(partsDir(), it.asset).delete() }
        }
        status[f.name] = St(Phase.VERIFYING, f.size, f.size, "Checking SHA-256…"); changed()
        val got = Fetcher.sha256(dest)
        if (!got.equals(f.sha256, true)) { dest.delete(); throw Fetcher.Failed("Checksum mismatch for ${f.name} — deleted; retry") }
        File(dir, f.name + ".sha256").writeText(f.sha256)
    }
}
