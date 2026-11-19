package app.belle.assistant.threed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.belle.assistant.R
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.ui.MainActivity

/**
 * While a photo → 3D run works: a foreground notification with the stage, a progress bar and the time left
 * ("Detailed shape 5/12 · about 9 min left") plus a Stop button, and a partial wake lock so the run keeps going with
 * the screen off. Stops by itself when the run ends.
 */
class Pixal3DService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wake: android.os.PowerManager.WakeLock? = null
    private var since = 0L
    // a run that is just starting (or resuming) gets a few seconds before "not running" means "finished"
    private val tick = object : Runnable { override fun run() {
        if (!Pixal3DJob.running && System.currentTimeMillis() - since > 8000) { stopSelf(); return }; notifyNow(); h.postDelayed(this, 2000) } }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "Making 3D models", NotificationManager.IMPORTANCE_LOW))
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, build())
        }.isSuccess
        if (!ok) { stopSelf(); return }
        // held for the WHOLE build (no timeout: a phone build can take hours); released in onDestroy, which runs when the
        // build finishes, fails or is stopped (the tick below stops the service), and by Android if the process dies
        runCatching { wake = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "belle:pixal3d")
            .apply { setReferenceCounted(false); @Suppress("WakelockTimeout") acquire() } }
        since = System.currentTimeMillis(); h.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) Pixal3DJob.cancel()
        // ACTION_RESUME, or a sticky restart after Android killed the process (intent == null)
        if ((intent?.action == ACTION_RESUME || intent == null) && !Pixal3DJob.running && Pixal3DJob.canResume(applicationContext)) {
            since = System.currentTimeMillis()
            Thread({ Pixal3DJob.resume(applicationContext) }, "pixal3d-resume").start()
        }
        return START_STICKY   // if Android kills the process mid-build, it restarts the service and the build resumes from its checkpoint
    }
    override fun onDestroy() { h.removeCallbacks(tick); runCatching { wake?.takeIf { it.isHeld }?.release() }; super.onDestroy() }

    private fun build(): Notification {
        val (what, f) = Avatar3DFiles.progress ?: ("Starting" to 0f)
        val stop = PendingIntent.getService(this, 11, Intent(this, Pixal3DService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (!Pixal3DJob.running) "Continuing the 3D model…" else "Making a 3D model · ${Pixal3DJob.etaText ?: "estimating time left…"}")
            .setContentText("$what · ${(f * 100).toInt()}% · on this phone, nothing is uploaded")
            .setProgress(100, (f * 100).toInt(), false).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setContentIntent(PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java).putExtra("tab", "settings").putExtra("page", "avatar3d"), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
    private fun notifyNow() = getSystemService(NotificationManager::class.java).notify(ID, build())

    companion object {
        private const val CH = "pixal3d"; private const val ID = 12; private const val ACTION_STOP = "stop"; private const val ACTION_RESUME = "resume"
        private const val CH_RESULT = "pixal3d_result"; private const val ID_RESULT = 13
        /** "Milly's 3D avatar is ready" (tap: the 3D page with the result and "Make Milly active"), or the failure and its reason. */
        fun notifyResult(ctx: Context, ok: Boolean, profileId: String, name: String, error: String?) = runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RESULT, "3D model results", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 14, Intent(ctx, MainActivity::class.java).putExtra("tab", "settings").putExtra("page", "avatar3d")
                .putExtra("p3d_profile", profileId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val possessive = if (name.endsWith("s")) "$name'" else "$name's"
            nm.notify(ID_RESULT, Notification.Builder(ctx, CH_RESULT).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(if (ok) "$possessive 3D avatar is ready" else "$possessive 3D avatar couldn't be made")
                .setContentText(if (ok) "Tap to see it, and to show it on the widget" else (error ?: "unknown error") + " · tap for the build log")
                .setStyle(Notification.BigTextStyle().bigText(if (ok) "Tap to see it, make $name the active assistant and show it on the widget." else
                    "Reason: ${error ?: "unknown error"}\nIf saved progress is left it continues the next time the app opens. Tap for the build log."))
                .setContentIntent(open).setAutoCancel(true).build())
        }
        /** Continue an unfinished run (app opened again, or the phone restarted). Safe to call any time. */
        fun resumeIfPending(ctx: Context): Boolean {
            if (!Pixal3DJob.canResume(ctx)) return false
            return runCatching { ctx.startForegroundService(Intent(ctx, Pixal3DService::class.java).setAction(ACTION_RESUME)) }.isSuccess
        }
        fun start(ctx: Context) { runCatching { ctx.startForegroundService(Intent(ctx, Pixal3DService::class.java)) } }
    }
}
