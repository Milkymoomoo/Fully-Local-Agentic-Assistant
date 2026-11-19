package app.belle.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.belle.assistant.Prefs
import app.belle.assistant.R

/** Fires our own reminders (and alarm/timer fallback when no clock app exists). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val raw = intent.getStringExtra("text") ?: "Reminder"
        val alarm = intent.getBooleanExtra("alarm", false)
        Reminders.fired(ctx, intent.getIntExtra("id", 0))
        // came due while the phone was off: say so, with the time it was for
        val text = if (intent.getBooleanExtra("late", false)) "$raw (was due ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(intent.getLongExtra("due", 0)))})" else raw
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = if (alarm) "alarms" else "reminders"
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(NotificationChannel(ch, if (alarm) "Alarms & timers" else "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                if (alarm) setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            })
        }
        nm.notify((System.currentTimeMillis() % 100000).toInt(), Notification.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle(if (alarm) text else "Reminder")
            .setContentText(text).setAutoCancel(true).setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER).build())
        AssistantRuntime.event("reminder fired: $text")
        if (!alarm) {
            val pending = goAsync()
            Thread {
                try { AssistantRuntime.init(ctx); AssistantRuntime.say("Reminder: $text") } catch (_: Throwable) {}
                pending.finish()
            }.start()
        }
    }
}

/** After a reboot or app update: re-arm reminders, restart listening (best effort), resume downloads. Android 14+ blocks microphone services started at boot; see README. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Android 11+ gives a microphone service started at boot only silence (14+ refuses it outright), so on those
        // versions don't pretend to listen: the service posts "Tap to resume listening" instead.
        // AlarmManager forgets everything on reboot and update: re-arm the saved reminders (late ones fire now)
        runCatching { Reminders.rescheduleAll(ctx) }
        // a photo → 3D run cut off by the restart continues from its last checkpoint (if Android refuses, opening the app does it)
        runCatching { app.belle.assistant.threed.Pixal3DService.resumeIfPending(ctx) }
        if (Prefs(ctx).serviceWanted) AssistantService.start(ctx, from = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "update")
        // Unfinished model downloads: Android 15+ no longer lets BOOT_COMPLETED start a dataSync foreground service,
        // so there (or if the start fails) one notification tap resumes them; earlier versions resume right away.
        val dl = app.belle.assistant.models.DownloadService
        if (dl.anyWanted(ctx)) {
            val resumed = android.os.Build.VERSION.SDK_INT < 35 && runCatching { dl.resumeAll(ctx) }.getOrDefault(false)
            if (!resumed) dl.postResumeNote(ctx)
        }
    }
}
