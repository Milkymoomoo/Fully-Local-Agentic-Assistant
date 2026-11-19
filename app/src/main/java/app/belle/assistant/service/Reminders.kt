package app.belle.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/** Schedules reminders through AlarmManager and re-schedules the saved ones after a reboot, update or force stop. */
object Reminders {
    fun store(ctx: Context) = ReminderStore(File(ctx.applicationContext.filesDir, "reminders.json"))

    fun schedule(ctx: Context, text: String, at: Long, alarm: Boolean): Reminder {
        val s = store(ctx); val r = Reminder(s.newId(at), at, text, alarm)
        s.add(r); arm(ctx, r, at, late = false); return r
    }

    /** Called when one fires: it's done, so it isn't re-armed after the next restart. */
    fun fired(ctx: Context, id: Int) { if (id != 0) store(ctx).remove(id) }

    /** Re-arms every saved reminder (same request codes, so calling this again only replaces them). */
    fun rescheduleAll(ctx: Context): Int {
        val plan = store(ctx).plan(System.currentTimeMillis())
        plan.forEach { arm(ctx, it.reminder, it.fireAt, it.late) }
        return plan.size
    }

    private fun arm(ctx: Context, r: Reminder, at: Long, late: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, r.id,
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("text", r.text).putExtra("alarm", r.alarm)
                .putExtra("due", r.at).putExtra("late", late),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
