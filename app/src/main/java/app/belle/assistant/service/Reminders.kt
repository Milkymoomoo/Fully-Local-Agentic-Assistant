package app.belle.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One reminder (or the app's own alarm/timer when no clock app exists) that must fire even after a restart. */
data class Reminder(val id: Int, val at: Long, val text: String, val alarm: Boolean)

/**
 * The reminders that are still due, kept in a small JSON file so they survive a reboot, an app update or a force stop
 * (Android forgets every AlarmManager alarm in those cases). Pure file logic, unit tested; [Reminders] does the Android side.
 */
class ReminderStore(private val file: File) {
    @Synchronized fun all(): List<Reminder> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"), o.optBoolean("alarm")) } }
    }.getOrDefault(emptyList())

    @Synchronized fun add(r: Reminder) = save(all().filter { it.id != r.id } + r)
    @Synchronized fun remove(id: Int) = save(all().filter { it.id != id })

    /** A request code no stored reminder uses (also the PendingIntent request code, so it can be replaced or cancelled). */
    @Synchronized fun newId(at: Long): Int {
        val used = all().map { it.id }.toSet()
        var id = (at / 1000 % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        while (id in used) id++
        return id
    }

    /**
     * What to schedule after a restart at [now]: future ones at their own time; ones that came due while the phone was
     * off fire a few seconds apart, marked late; ones more than [dropAfterMs] overdue are forgotten.
     */
    @Synchronized fun plan(now: Long, dropAfterMs: Long = DROP_AFTER_MS): List<Planned> {
        val keep = all().filter { now - it.at <= dropAfterMs }
        save(keep)
        var late = 0
        return keep.sortedBy { it.at }.map { r -> if (r.at > now) Planned(r, r.at, false) else Planned(r, now + LATE_GAP_MS * ++late, now - r.at > LATE_AFTER_MS) }
    }

    data class Planned(val reminder: Reminder, val fireAt: Long, val late: Boolean)

    private fun save(list: List<Reminder>) {
        file.parentFile?.mkdirs()
        val a = JSONArray(); list.forEach { r -> a.put(JSONObject().put("id", r.id).put("at", r.at).put("text", r.text).put("alarm", r.alarm)) }
        val tmp = File(file.path + ".tmp"); tmp.writeText(a.toString()); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val LATE_GAP_MS = 5_000L
        /** An alarm Android delivers a little late (Doze) isn't "missed"; only one over a minute overdue is announced as late. */
        const val LATE_AFTER_MS = 60_000L
        const val DROP_AFTER_MS = 7L * 24 * 3600_000
    }
}

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
