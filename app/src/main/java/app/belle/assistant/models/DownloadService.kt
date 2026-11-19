package app.belle.assistant.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.belle.assistant.R
import app.belle.assistant.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps big downloads going when you leave the app or the screen turns off: a dataSync foreground service with a
 * progress notification, a partial wake lock (CPU) and a Wi-Fi lock (radio) held while anything downloads. It is
 * sticky: if Android kills the process, the restarted service resumes an install the user started (see
 * [app.belle.assistant.threed.Pixal3DInstall.resumeIfWanted]).
 */
class DownloadService : Service() {
    private val listener: () -> Unit = { update() }
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val n = build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        }.isSuccess
        if (!ok) { postResumeNote(this); stopSelf(); return }   // Android refused a background start: ask for one tap instead
        Downloads.addListener(listener)
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: the system restarted this sticky service after killing the process. Pick the install back up.
        if (intent == null && active.get() <= 0) resumeAll(applicationContext)
        if (active.get() <= 0) { releaseLocks(); stopSelf() }
        update(); return START_STICKY
    }

    override fun onDestroy() { Downloads.removeListener(listener); releaseLocks(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            if (wake == null) wake = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "belle:downloads").apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        }
        runCatching {
            if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "belle:downloads").apply { setReferenceCounted(false); acquire() }
        }
    }
    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
    }

    private fun build(): Notification {
        val running = Downloads.state.entries.filter { it.value.phase in setOf(Downloads.Phase.QUEUED, Downloads.Phase.DOWNLOADING, Downloads.Phase.VERIFYING, Downloads.Phase.INSTALLING) }
        val done = running.sumOf { it.value.done }; val total = running.sumOf { it.value.total }.coerceAtLeast(1)
        val pct = (done * 100 / total).toInt()
        val eta = Downloads.eta(this); eta.sample(done); saveSpeed(this)
        val title = if (running.isEmpty()) "Downloads finished" else "Downloading ${running.size} model${if (running.size > 1) "s" else ""} · $pct% · ${eta.text(total - done)}"
        val speed = eta.bytesPerSec?.let { " · %.1f MB/s".format(it / 1e6) } ?: ""
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_mic).setContentTitle(title)
            .setContentText("${done / 1_000_000} of ${total / 1_000_000} MB$speed · from Hugging Face / GitHub only")
            .setProgress(100, pct, running.any { it.value.phase == Downloads.Phase.QUEUED })
            .setOngoing(running.isNotEmpty()).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).putExtra("tab", "settings").putExtra("page", "getmodels"), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun update() { getSystemService(NotificationManager::class.java).notify(ID, build()) }

    companion object {
        private const val CH = "downloads"; private const val ID = 7
        private var lastSave = 0L
        /** Keep the smoothed speed (every 10 s) so the next queue's first estimate is realistic. */
        fun saveSpeed(ctx: Context) {
            val now = System.currentTimeMillis(); if (now - lastSave < 10_000) return; lastSave = now
            Downloads.eta(ctx).bytesPerSec?.let { ctx.getSharedPreferences("downloads_eta", Context.MODE_PRIVATE).edit().putFloat("bytes_per_sec", it.toFloat()).apply() }
        }
        private val active = AtomicInteger(0)
        fun started(ctx: Context) { active.incrementAndGet(); runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) } }
        fun finished(ctx: Context) { if (active.decrementAndGet() <= 0) runCatching { ctx.startService(Intent(ctx, DownloadService::class.java).setAction("finished")) } }

        /** Anything the user started downloading that hasn't finished (one-tap models or the 3D generation set). */
        fun anyWanted(ctx: Context): Boolean =
            Downloads.wantedIds(ctx).isNotEmpty() || app.belle.assistant.threed.Pixal3DInstall.wanted(ctx)

        /** Resume every unfinished download the user started. Call only where a foreground service may start. */
        fun resumeAll(ctx: Context): Boolean {
            val a = runCatching { Downloads.resumeWanted(ctx) > 0 }.getOrDefault(false)
            val b = runCatching { app.belle.assistant.threed.Pixal3DInstall.resumeIfWanted(ctx) }.getOrDefault(false)
            if (a || b) cancelResumeNote(ctx)
            return a || b
        }

        /** "Tap to finish installing" when Android won't let the download restart by itself (Android 15+ after a reboot). */
        fun postResumeNote(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Model downloads", NotificationManager.IMPORTANCE_LOW))
            val i = Intent(ctx, MainActivity::class.java).putExtra("tab", "settings").putExtra("page", if (app.belle.assistant.threed.Pixal3DInstall.wanted(ctx)) "avatar3d" else "getmodels").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            nm.notify(RESUME_ID, Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Tap to finish your downloads")
                .setContentText("Model downloads stopped when the phone restarted or the app was closed. They continue where they left off.")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 6, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        fun cancelResumeNote(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(RESUME_ID)
        private const val RESUME_ID = 8
    }
}
