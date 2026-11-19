package app.belle.assistant.threed

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.belle.assistant.models.DownloadService
import app.belle.assistant.models.Downloads

/**
 * The one-button "Install 3D generation": downloads the Pixal3D model set listed in assets/pixal3d_models.json from the
 * project's GitHub Release (only github.com and its download CDN are allowed), resumes after drops or restarts, checks
 * every file's SHA-256 and installs into [Pixal3DJob.dir] — the same place and the same checksum markers as the
 * folder import, which stays available as the offline alternative. Runs under the downloads foreground service.
 */
object Pixal3DInstall {
    private const val NOTE_ID = "3d:install"
    @Volatile private var inst: ModelInstaller? = null
    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var lastUi = 0L
    @Volatile private var service = false

    fun manifest(ctx: Context): ModelManifest = ModelManifest.parse(ctx.assets.open("pixal3d_models.json").use { it.readBytes().decodeToString() })

    fun get(ctx: Context): ModelInstaller = inst ?: synchronized(this) {
        inst ?: ModelInstaller(Pixal3DJob.dir(ctx.applicationContext), manifest(ctx)).also { i -> i.addListener { onChange(ctx.applicationContext, i) }; inst = i }
    }

    fun addListener(l: () -> Unit) { uiListeners += l }
    fun removeListener(l: () -> Unit) { uiListeners -= l }

    /** Licences the user must accept first (the DINOv3 License); remembered once accepted. */
    fun pendingLicenses(ctx: Context) = get(ctx).manifest.licenses.filter { it.mustAccept && !prefs(ctx).getBoolean("accepted_" + it.id, false) }
    fun accept(ctx: Context, id: String) = prefs(ctx).edit().putBoolean("accepted_$id", true).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pixal3d_install", Context.MODE_PRIVATE)

    /** null when it can start; otherwise why not (licence not accepted, not enough space). */
    fun blocker(ctx: Context): String? {
        pendingLicenses(ctx).firstOrNull()?.let { return "Accept the ${it.title} first" }
        val need = get(ctx).remainingBytes() + 300_000_000L
        val free = Pixal3DJob.dir(ctx).usableSpace
        if (free < need) return "Needs ${"%.1f".format(need / 1e9)} GB free on the phone, ${"%.1f".format(free / 1e9)} GB available"
        return null
    }

    fun installAll(ctx: Context) { if (blocker(ctx) == null) { ensureService(ctx); get(ctx).installAll() } }
    fun retryAll(ctx: Context) { if (blocker(ctx) == null) { ensureService(ctx); get(ctx).retryAll() } }
    fun retry(ctx: Context, name: String) { if (blocker(ctx) == null) { ensureService(ctx); get(ctx).retry(name) } }
    fun cancel(ctx: Context) = get(ctx).cancel()

    /** True when an install the user started is unfinished (kept across app kills and reboots). */
    fun wanted(ctx: Context): Boolean = runCatching { get(ctx).wanted }.getOrDefault(false)

    /**
     * Carry on with an install the user started (app restarted, process killed, phone rebooted). Must be called where a
     * foreground service may start (app visible, a notification tap, or BOOT_COMPLETED before Android 15).
     * Returns true when downloading continues.
     */
    fun resumeIfWanted(ctx: Context): Boolean {
        val i = get(ctx)
        if (!i.wanted) return false
        if (i.busy) return true
        if (blocker(ctx) != null) return false
        ensureService(ctx); return i.resumeIfWanted()
    }

    private fun ensureService(ctx: Context) = synchronized(this) { if (!service) { service = true; DownloadService.started(ctx.applicationContext) } }

    private fun onChange(ctx: Context, i: ModelInstaller) {
        val busy = i.busy
        Downloads.external(NOTE_ID, if (busy) Downloads.St(Downloads.Phase.DOWNLOADING, i.bytesDone, i.manifest.totalBytes, "3D generation") else null)
        if (!busy) synchronized(this) { if (service) { service = false; DownloadService.finished(ctx) } }
        val now = System.currentTimeMillis()
        if (!busy || now - lastUi > 250) { lastUi = now; main.post { uiListeners.forEach { it() } } }
    }
}
