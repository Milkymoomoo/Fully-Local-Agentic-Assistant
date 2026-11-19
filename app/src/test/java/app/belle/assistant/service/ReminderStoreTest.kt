package app.belle.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReminderStoreTest {
    private fun tmp() = File(Files.createTempDirectory("rem").toFile(), "reminders.json")

    @Test fun savedRemindersSurviveARestart() {
        val f = tmp()
        val a = ReminderStore(f)
        a.add(Reminder(a.newId(1_000_000), 1_000_000, "take the bins out", false))
        a.add(Reminder(a.newId(2_000_000), 2_000_000, "Alarm", true))
        // "reboot": a brand-new store reading the same file
        val b = ReminderStore(f).all()
        assertEquals(listOf("take the bins out", "Alarm"), b.map { it.text })
        assertTrue(b[1].alarm)
    }

    @Test fun firedRemindersAreNotRearmed() {
        val s = ReminderStore(tmp())
        val r = Reminder(s.newId(5_000), 5_000, "call mum", false); s.add(r)
        s.remove(r.id)
        assertTrue(s.plan(now = 1_000).isEmpty())
    }

    @Test fun planKeepsFutureTimesAndFiresMissedOnesLateAndStaggered() {
        val s = ReminderStore(tmp())
        val now = 10_000_000L
        s.add(Reminder(1, now + 60_000, "future", false))
        s.add(Reminder(2, now - 3_600_000, "missed an hour ago", false))
        s.add(Reminder(3, now - 1_800_000, "missed half an hour ago", false))
        s.add(Reminder(4, now - 10_000, "just due (Doze delay)", false))
        val p = s.plan(now).associateBy { it.reminder.id }
        assertEquals(now + 60_000, p[1]!!.fireAt); assertFalse(p[1]!!.late)
        assertEquals(now + ReminderStore.LATE_GAP_MS, p[2]!!.fireAt); assertTrue(p[2]!!.late)
        assertEquals(now + 2 * ReminderStore.LATE_GAP_MS, p[3]!!.fireAt); assertTrue(p[3]!!.late)
        assertFalse("a few seconds overdue isn't announced as missed", p[4]!!.late)
    }

    @Test fun veryOldRemindersAreDropped() {
        val f = tmp(); val s = ReminderStore(f); val now = 30L * 24 * 3600_000
        s.add(Reminder(1, now - ReminderStore.DROP_AFTER_MS - 1, "ancient", false))
        s.add(Reminder(2, now + 1, "soon", false))
        assertEquals(listOf(2), s.plan(now).map { it.reminder.id })
        assertEquals(listOf(2), ReminderStore(f).all().map { it.id })
    }

    @Test fun idsAreUniqueAndPlanIsIdempotent() {
        val s = ReminderStore(tmp())
        val id1 = s.newId(42_000); s.add(Reminder(id1, 42_000, "a", false))
        val id2 = s.newId(42_000)
        assertTrue(id1 != id2)
        s.add(Reminder(id2, 42_000, "b", false))
        val first = s.plan(1_000); val second = s.plan(1_000)
        assertEquals(first, second)   // re-arming on every app start just replaces the same alarms
    }

    @Test fun corruptFileMeansNoReminders() {
        val f = tmp(); f.parentFile.mkdirs(); f.writeText("{not json")
        assertTrue(ReminderStore(f).all().isEmpty())
    }
}
