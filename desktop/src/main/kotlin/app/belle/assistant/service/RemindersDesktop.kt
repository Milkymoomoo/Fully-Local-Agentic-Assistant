package app.belle.assistant.service

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop twin of the phone's reminder scheduler, over the same [ReminderStore] file (reminders.json), so reminders
 * survive closing the app, a reboot or a power cut: they are re-armed at every start ([rescheduleAll]), and one that
 * came due while the PC was off fires late, with a note (the store's own plan logic, shared with the phone).
 */
object Reminders {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "reminders").apply { isDaemon = true } }
    /** Armed reminders by id: (reminder, wall-clock time to fire, came due while off). Checked every few seconds against the
     *  wall clock, so a PC that slept through a reminder fires it on waking (a plain timer would count only awake time). */
    private val armed = HashMap<Int, Triple<Reminder, Long, Boolean>>()
    private var ticking: ScheduledFuture<*>? = null
    private var tickCtx: Context? = null
    @Volatile var onFire: (Reminder, late: Boolean) -> Unit = { _, _ -> }

    fun store(ctx: Context) = ReminderStore(File(ctx.applicationContext.filesDir, "reminders.json"))

    fun schedule(ctx: Context, text: String, at: Long, alarm: Boolean): Reminder {
        val s = store(ctx); val r = Reminder(s.newId(at), at, text, alarm)
        s.add(r); arm(ctx, r, at, late = false); return r
    }
    fun fired(ctx: Context, id: Int) { if (id != 0) store(ctx).remove(id) }
    fun rescheduleAll(ctx: Context): Int {
        val plan = store(ctx).plan(System.currentTimeMillis())
        plan.forEach { arm(ctx, it.reminder, it.fireAt, it.late) }
        return plan.size
    }
    fun pending(ctx: Context): List<Reminder> = store(ctx).all()
    fun cancel(ctx: Context, id: Int) { synchronized(armed) { armed.remove(id) }; store(ctx).remove(id) }

    private fun arm(ctx: Context, r: Reminder, at: Long, late: Boolean) = synchronized(armed) {
        armed[r.id] = Triple(r, at, late); tickCtx = ctx.applicationContext
        if (ticking == null) ticking = timer.scheduleWithFixedDelay({ tick() }, 0, 5, TimeUnit.SECONDS)
        timer.execute { tick() }
    }
    private fun tick() {
        val now = System.currentTimeMillis(); val ctx = tickCtx ?: return
        val due = synchronized(armed) { armed.values.filter { it.second <= now }.also { d -> d.forEach { armed.remove(it.first.id) } } }
        due.forEach { (r, _, late) -> runCatching { onFire(r, late || now - r.at > 120_000) }; fired(ctx, r.id) }
    }
}
