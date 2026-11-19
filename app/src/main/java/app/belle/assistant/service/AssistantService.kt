package app.belle.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.belle.assistant.Prefs
import app.belle.assistant.R
import app.belle.assistant.speech.SAMPLE_RATE
import app.belle.assistant.ui.MainActivity

/** Foreground service (type: microphone) that keeps the wake word listening. */
class AssistantService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val listener: () -> Unit = { updateNotification(); app.belle.assistant.ui.AssistantWidget.onStateChanged(this) }
    private var avatar: app.belle.assistant.avatar.AvatarOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            Prefs(this).serviceWanted = false
            stopSelfCleanly(); return START_NOT_STICKY
        }
        // Where did this start come from? Android 11+ only gives a microphone service real audio when it was started
        // from something the user can see or touched (an open app, a widget/notification tap, the default-assistant
        // service). Started from the background (boot, a sticky restart after Android killed us, an alarm) the recorder
        // just gets silence, so instead of pretending to listen we ask for one tap.
        val from = intent?.getStringExtra(EXTRA_FROM) ?: if (intent == null) "restart" else "background"
        val visible = from in VISIBLE_SOURCES
        AssistantRuntime.init(this)
        if (Build.VERSION.SDK_INT >= 30 && !visible && !(inForeground && AssistantRuntime.Diag.micAllowed)) {
            AssistantRuntime.event("service: start from $from can't use the microphone on Android 11+; asking for a tap")
            AssistantRuntime.Diag.micAllowed = false
            postTapToStart(if (from == "boot") "after a restart" else "after Android stopped me")
            if (!inForeground) { stopSelf(); return START_NOT_STICKY }
            return START_STICKY
        }
        // Re-calling startForeground from the background throws on Android 14+ (microphone is "while in use"), so
        // only (re)enter the foreground when not there yet, or when the start is user-visible (that also refreshes the
        // "while in use" microphone access on Android 11–13).
        if (!inForeground || visible) {
            if (!goForeground()) {
                if (!inForeground) { postTapToStart("after Android stopped me"); stopSelf(); return START_NOT_STICKY }
            } else {
                cancelTapToStart()
                AssistantRuntime.Diag.startedFrom = from; AssistantRuntime.Diag.micAllowed = true
            }
        }
        if (!running) {
            running = true
            acquireWakeLock()
            AssistantRuntime.addListener(listener)
            AssistantRuntime.load(this)
            startMic()
            watchRecording()
            // the floating avatar decodes pictures / builds its mesh: do it after onStartCommand has returned, so a
            // slow phone never trips the "executing service" ANR timeout
            android.os.Handler(mainLooper).post { if (running && avatar == null) avatar = app.belle.assistant.avatar.AvatarOverlay(this).also { it.show() } }
        } else if (visible && (AssistantRuntime.micSilenced || AssistantRuntime.Diag.systemSilenced == true)) {
            // a fresh recorder under the refreshed foreground state gets real audio again
            startMic()
        }
        if (Prefs(this).keepCpuAwake) acquireWakeLock() else releaseWakeLock()
        when (action) {
            ACTION_LISTEN -> AssistantRuntime.listenNow(this)
            ACTION_END -> AssistantRuntime.endSession(this, "End button")
            ACTION_AVATAR -> { avatar?.show(); app.belle.assistant.ui.AssistantWidget.updateAll(this) }
            ACTION_RELOAD -> AssistantRuntime.load(this, reloadLlm = true)
            ACTION_TEXT -> intent?.getStringExtra("text")?.let { AssistantRuntime.handleText(this, it) }
            ACTION_REARM -> rearmMic("app opened")
            ACTION_INJECT -> if (AssistantRuntime.isDebuggable(this)) intent?.getStringExtra("path")?.let { AssistantRuntime.injectWav(it) }
            ACTION_BOOK -> {
                val cmd = intent?.getStringExtra("cmd") ?: "toggle"
                // debug builds only: import a file already on the phone (tests); the app imports through the file picker
                if (cmd == "import") { if (AssistantRuntime.isDebuggable(this)) intent?.getStringExtra("path")?.let { AssistantRuntime.importBook(this, java.io.File(it)) } }
                else AssistantRuntime.bookControl(this, cmd, intent?.getStringExtra("arg"))
            }
        }
        return START_STICKY
    }

    /** Android's own view of our recorder: isClientSilenced = true means it's feeding us silence (background capture
     *  blocked, or a call / another app has priority). */
    private var recCallback: android.media.AudioManager.AudioRecordingCallback? = null
    @Volatile private var recSession = -1
    private fun watchRecording() {
        if (recCallback != null) return
        val am = getSystemService(android.media.AudioManager::class.java)
        val cb = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) = check(configs)
        }
        recCallback = cb
        am.registerAudioRecordingCallback(cb, android.os.Handler(mainLooper))
        check(am.activeRecordingConfigurations)
    }
    private fun check(configs: List<android.media.AudioRecordingConfiguration>) {
        val mine = configs.firstOrNull { it.clientAudioSessionId == recSession } ?: return
        val silenced = mine.isClientSilenced
        if (silenced == AssistantRuntime.Diag.systemSilenced) return
        AssistantRuntime.Diag.systemSilenced = silenced
        AssistantRuntime.event(if (silenced) "mic: Android reports it is silencing my recorder" else "mic: Android reports my recorder gets audio")
        if (silenced && !app.belle.assistant.ui.MainActivity.onScreen) postTapToStart("the microphone was paused by Android") else if (!silenced) cancelTapToStart()
        AssistantRuntime.setMicSilenced(silenced)
    }

    private fun goForeground(): Boolean {
        val n = buildNotification()
        return try {
            val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE.takeIf { Build.VERSION.SDK_INT >= 34 } ?: 0)
            } else startForeground(NOTIF_ID, n)
            inForeground = true
            true
        } catch (e: Exception) {
            // Android 14+ refuses to start a microphone service from the background (e.g. after reboot).
            Log.e("BelleService", "startForeground refused: ${e.message}")
            AssistantRuntime.event("service: Android refused to start listening in the background (${e.javaClass.simpleName}). Open the app once.")
            false
        }
    }

    @Volatile private var micGeneration = 0
    @Volatile private var lastAudioAt = 0L

    /** Restart the recorder (after Android silenced it, another app took the mic, or a read error). */
    private fun rearmMic(why: String) {
        if (!running) return
        if (AssistantRuntime.micSilenced || System.currentTimeMillis() - lastAudioAt > 3000 || thread?.isAlive != true) {
            AssistantRuntime.event("mic: re-arming ($why)")
            startMic()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AssistantRuntime.event("mic: no RECORD_AUDIO permission; wake word disabled until granted")
            return
        }
        val gen = ++micGeneration
        thread = Thread({
            var silentSince = 0L; var lastRetry = 0L
            while (running && gen == micGeneration) {
                val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val rec = try {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, SAMPLE_RATE * 4))
                } catch (e: Exception) { AssistantRuntime.event("mic: failed ${e.message}"); null }
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec?.release(); AssistantRuntime.event("mic: not initialized, retrying in 5 s"); Thread.sleep(5000); continue
                }
                rec.startRecording()
                recSession = rec.audioSessionId
                AssistantRuntime.Diag.systemSilenced = null
                runCatching { check(getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations) }
                AssistantRuntime.event("mic: recording (session ${rec.audioSessionId})")
                val buf = FloatArray(1600)
                var restart = false
                while (running && gen == micGeneration && !restart) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) { AssistantRuntime.event("mic: read error $n, restarting"); restart = true; Thread.sleep(500); break }
                    if (n == 0) continue
                    lastAudioAt = System.currentTimeMillis()
                    AssistantRuntime.Diag.onChunk(if (n == buf.size) buf else buf.copyOf(n))
                    // Android delivers exact digital silence when it blocks background capture or another app has the mic.
                    var allZero = true
                    for (i in 0 until n) if (buf[i] != 0f) { allZero = false; break }
                    if (allZero) {
                        if (silentSince == 0L) silentSince = lastAudioAt
                        if (lastAudioAt - silentSince > 4000 && !AssistantRuntime.micSilenced) {
                            AssistantRuntime.setMicSilenced(true)
                            if (!app.belle.assistant.ui.MainActivity.onScreen) postTapToStart("the microphone was paused by Android")
                            AssistantRuntime.event("mic: Android is giving me silence (microphone blocked in the background or used by another app)")
                        }
                        // try a fresh recorder every 30 s; once the other app lets go this brings the wake word back
                        if (AssistantRuntime.micSilenced && lastAudioAt - lastRetry > 30_000) { lastRetry = lastAudioAt; restart = true }
                    } else {
                        silentSince = 0L
                        if (AssistantRuntime.micSilenced && AssistantRuntime.Diag.systemSilenced != true) { AssistantRuntime.setMicSilenced(false); cancelTapToStart(); AssistantRuntime.event("mic: hearing audio again") }
                    }
                    AssistantRuntime.onAudio(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                }
                runCatching { rec.stop() }; rec.release()
            }
        }, "belle-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    /** Keeps the CPU running with the screen off so the wake word keeps being checked (the screen may sleep). */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true || !Prefs(this).keepCpuAwake) return
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "belle:wakeword").apply { setReferenceCounted(false); acquire() }
    }
    private fun releaseWakeLock() { runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null }

    /** Android refused to start the microphone from the background (boot, Android 14+): one tap brings it back. */
    private fun postTapToStart(why: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) buildNotification()
        // Tapping a notification counts as a user-visible start, so this goes straight to the service — no app screen.
        val resume = PendingIntent.getForegroundService(this, 4, Intent(this, AssistantService::class.java).setAction(ACTION_REARM)
            .putExtra(EXTRA_FROM, "notification"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_TAP, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Tap to resume listening")
            .setContentText("Wake words are paused $why — Android only lets the microphone run after you tap.")
            .setStyle(Notification.BigTextStyle().bigText("Wake words are paused $why. Android only lets an app use the microphone in the background after you start it yourself — one tap here and I keep listening, even with the app closed."))
            .setContentIntent(resume).setAutoCancel(true).build())
    }
    private fun cancelTapToStart() = getSystemService(NotificationManager::class.java).cancel(NOTIF_TAP)

    private fun stopSelfCleanly() {
        running = false; inForeground = false; micGeneration++; releaseWakeLock()
        AssistantRuntime.removeListener(listener)
        avatar?.hide(); avatar = null
        AssistantRuntime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { recCallback?.let { runCatching { getSystemService(android.media.AudioManager::class.java).unregisterAudioRecordingCallback(it) } }; recCallback = null
        if (running && Prefs(this).serviceWanted) postTapToStart("after Android stopped me")
        running = false; inForeground = false; micGeneration++; releaseWakeLock(); avatar?.hide(); AssistantRuntime.removeListener(listener); super.onDestroy() }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the assistant is ready. Audio never leaves your phone."
            })
        }
        val open = if (AssistantRuntime.micSilenced) PendingIntent.getForegroundService(this, 5, Intent(this, AssistantService::class.java).setAction(ACTION_REARM).putExtra(EXTRA_FROM, "notification"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("rearm", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val talk = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_LISTEN).putExtra(EXTRA_FROM, "notification"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getService(this, 3, Intent(this, AssistantService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        val active = AssistantRuntime.inSession
        val reading = AssistantRuntime.bookNow()
        val title = Prefs(this).assistantName + if (AssistantRuntime.micSilenced) " · microphone paused by Android — tap to resume" else when (AssistantRuntime.ui) {
            AssistantRuntime.Ui.LISTENING, AssistantRuntime.Ui.WAKING -> " · listening to you"
            AssistantRuntime.Ui.IDLE -> if (AssistantRuntime.mode == AssistantRuntime.Mode.WAKE && Prefs(this).wakeWordEnabled) " · Listening for wake words" else ""
            AssistantRuntime.Ui.THINKING -> " · thinking"
            AssistantRuntime.Ui.SPEAKING -> " · speaking"
            else -> ""
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(reading?.let { (line, playing) -> (if (playing) "Reading: " else "Paused: ") + line } ?: AssistantRuntime.status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // While a conversation is going: "End conversation". Otherwise: "Talk".
            .addAction(if (active) Notification.Action.Builder(null, "End conversation", end).build()
                       else Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .apply {
                // "Read to me": play / pause the book right from the notification
                if (reading != null) addAction(Notification.Action.Builder(null, if (reading.second) "Pause book" else "Play book",
                    BookPlaybackService.pending(this@AssistantService, "toggle", 6)).build())
            }
            .build()
    }

    private var lastShown = ""
    private fun updateNotification() {
        if (!running) return
        val key = "${AssistantRuntime.micSilenced}|${AssistantRuntime.ui}|${AssistantRuntime.inSession}|${AssistantRuntime.status}|${AssistantRuntime.bookNow()}"
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private fun MainActivityVisible() = app.belle.assistant.ui.MainActivity.onScreen
        @Volatile var inForeground = false; private set
        const val CHANNEL = "listening"
        const val NOTIF_ID = 7
        const val NOTIF_TAP = 8
        const val ACTION_REARM = "app.belle.assistant.REARM"
        const val ACTION_START = "app.belle.assistant.START"
        const val ACTION_STOP = "app.belle.assistant.STOP"
        const val ACTION_LISTEN = "app.belle.assistant.LISTEN"
        const val ACTION_END = "app.belle.assistant.END"
        const val ACTION_AVATAR = "app.belle.assistant.AVATAR"
        const val ACTION_RELOAD = "app.belle.assistant.RELOAD"
        const val ACTION_TEXT = "app.belle.assistant.TEXT"
        const val ACTION_INJECT = "app.belle.assistant.INJECT"
        const val ACTION_BOOK = "app.belle.assistant.BOOK"
        const val EXTRA_FROM = "from"
        /** Starts Android treats as user-visible (the microphone works afterwards, even once the app is closed). */
        val VISIBLE_SOURCES = setOf("app", "widget", "notification", "assistant", "debug")

        /** [from] defaults to "app" when called from an Activity, else "background". */
        fun start(ctx: Context, action: String = ACTION_START, from: String? = null) {
            val src = from ?: if (ctx is android.app.Activity || (MainActivityVisible() && ctx !is Service)) "app" else "background"
            val i = Intent(ctx, AssistantService::class.java).setAction(action).putExtra(EXTRA_FROM, src)
            if (action != ACTION_STOP) Prefs(ctx).serviceWanted = true
            // Already running in the foreground: a plain startService is allowed and carries no "must call
            // startForeground within 10 s" deadline, so a busy main thread can never turn a button press into an ANR.
            // While our own UI is on screen the app is in the foreground, so a plain startService is allowed too and the
            // service then enters the foreground itself — no "startForeground within 10 s" deadline that a busy first
            // frame (slow phone, big background decode) could miss and crash the whole app with.
            val appVisible = ctx is android.app.Activity || MainActivityVisible()
            try { if (inForeground || appVisible) ctx.startService(i) else ctx.startForegroundService(i) } catch (e: Exception) {
                // background-start restriction (e.g. the UI just went away): the foreground-service start still works
                if (!inForeground) runCatching { ctx.startForegroundService(i) }
                AssistantRuntime.event("service: start refused: ${e.message}")
            }
        }
    }
}
