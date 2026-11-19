package app.belle.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.belle.assistant.R
import app.belle.assistant.ui.MainActivity

/**
 * "Read to me" in the background (t493u). While a book is reading — started from the Read to me page, the widget,
 * the notification or by voice — this foreground service (type mediaPlayback) keeps the app's process running with
 * the screen off or another app in front, holds a CPU wake lock (the voice is made on the phone, sentence by
 * sentence), and shows a media notification with play / pause and chapter buttons that also works on the lock
 * screen. It is separate from the assistant's microphone service, so reading never depends on the assistant being on.
 * When reading stops it stays in the foreground for [GRACE_MS] (a quick resume finds the voice still loaded), then
 * leaves the foreground and keeps a dismissible notification with a Play button.
 */
class BookPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var inFg = false
    /** Stop / Unload asked: no more notification updates from this instance (a late state change must not re-post it). */
    @Volatile private var closing = false
    private var lastKey = ""
    private val listener: () -> Unit = { main.post { sync() } }
    private val leave = Runnable { if (!active()) { stopForegroundCompat(); stopSelf() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AssistantRuntime.init(this)
        AssistantRuntime.addListener(listener)
        session = MediaSession(this, "belle-book").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = control("play")
                override fun onPause() = control("pause")
                override fun onStop() = control("stop")
                override fun onSkipToNext() = control("next")
                override fun onSkipToPrevious() = control("prev")
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // into the foreground first: a start from the widget / notification must call startForeground within seconds
        goForeground()
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) AssistantRuntime.bookControl(this, cmd, intent.getStringExtra("arg"))
        closing = cmd == "stop" || cmd == "unload" || cmd == "unloadAll"
        if (closing) {   // Stop: no notification left, the service ends
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            inFg = false; getSystemService(NotificationManager::class.java).cancel(NOTIF_ID); stopSelf(); return START_NOT_STICKY
        }
        sync()
        return START_NOT_STICKY
    }

    private fun control(cmd: String) = AssistantRuntime.bookControl(this, cmd)

    private fun active(): Boolean { val r = AssistantRuntime.reader; return r.playing || r.bargePaused || r.waitingForAudio }

    private fun sync() {
        if (closing) { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID); return }
        val r = AssistantRuntime.reader
        val on = active()
        if (on) {
            main.removeCallbacks(leave)
            if (!inFg) goForeground()
            if (wake?.isHeld != true) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "belle:book").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        } else {
            runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
            main.removeCallbacks(leave); main.postDelayed(leave, GRACE_MS)
        }
        val key = "${r.book?.id}|${r.chapterTitle}|$on"
        if (key == lastKey) return
        lastKey = key
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (on) 1f else 0f).build())
        session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, r.book?.title ?: "Read to me")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, r.chapterTitle).putString(MediaMetadata.METADATA_KEY_ALBUM, "Read to me").build())
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(on))
        app.belle.assistant.ui.AssistantWidget.onStateChanged(this)
    }

    private fun goForeground() {
        val n = build(active())
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIF_ID, n)
            inFg = true; fg = true
        }.onFailure { AssistantRuntime.event("book: couldn't keep reading in the background: ${it.message}") }
    }
    private fun stopForegroundCompat() {
        // keep the notification (with Play) when leaving the foreground; it can be swiped away
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        inFg = false; fg = false
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(false))
    }

    private fun build(on: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Read to me", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a book is being read aloud, with play / pause"; lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false) })
        val r = AssistantRuntime.reader
        fun act(icon: Int, title: String, cmd: String, code: Int) = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pending(this, cmd, code)).build()
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("page", "books").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_book)
            .setContentTitle(r.book?.title ?: "Read to me")
            .setContentText(r.chapterTitle.ifEmpty { if (on) "Reading" else "Paused" })
            .setSubText(if (on) "Reading" else "Paused")
            .setContentIntent(open)
            .setOngoing(on).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(act(R.drawable.ic_prev, "Previous chapter", "prev", 41))
            .addAction(if (on) act(R.drawable.ic_pause, "Pause", "pause", 42) else act(R.drawable.ic_play, "Play", "play", 42))
            .addAction(act(R.drawable.ic_next, "Next chapter", "next", 43))
            .addAction(act(R.drawable.ic_stop, "Stop", "stop", 44))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 3))
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacks(leave)
        // ended by Stop / Unload (not a pause that timed out): no notification left behind
        if (closing) getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (inFg) { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        AssistantRuntime.removeListener(listener)
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        session?.release(); session = null
        inFg = false; fg = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "book"
        const val NOTIF_ID = 31
        const val ACTION = "app.belle.assistant.BOOK_PLAYBACK"
        /** After reading stops: stay in the foreground this long (a resume within it starts at once), then leave. */
        const val GRACE_MS = 5 * 60_000L

        /** A PendingIntent that runs a book command [cmd] through this service (widget, notification): allowed to
         *  start a foreground service from the background, and never starts the assistant or loads its models. */
        fun pending(ctx: Context, cmd: String, code: Int): PendingIntent =
            PendingIntent.getForegroundService(ctx, code, Intent(ctx, BookPlaybackService::class.java).setAction("$ACTION.$cmd").putExtra("cmd", cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Reading started somewhere (page, voice, a resume after a call): make sure the service runs in the foreground. */
        @Volatile var fg = false; private set
        /** Stop / unload: the service ends and its media notification goes away. */
        fun dismiss(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, BookPlaybackService::class.java)) }
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
        fun ensure(ctx: Context) {
            if (fg) return
            val i = Intent(ctx, BookPlaybackService::class.java).setAction(ACTION)
            try { ctx.startForegroundService(i) } catch (e: Exception) {
                // Android 12+ refuses a foreground start from the background (e.g. a resume after a phone call while the app
                // is closed); the assistant's own foreground service keeps the process running then
                AssistantRuntime.event("book: background service start refused (${e.javaClass.simpleName}); assistant service in foreground=${AssistantService.inForeground}")
            }
        }
    }
}
