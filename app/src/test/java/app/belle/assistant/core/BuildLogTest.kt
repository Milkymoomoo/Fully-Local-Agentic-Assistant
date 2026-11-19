package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildLogTest {
    private fun log() = BuildLog(File(Files.createTempDirectory("bl").toFile(), "pixal3d_history.ck"))

    @Test fun recordsARunFromStartToDone() {
        val l = log()
        l.start(1, "milly1", "Milly", 1_000)
        l.progress(1, "Rough shape 3/8", 2100); l.progress(1, "Detailed shape 7/12", 3300); l.progress(1, "Decoding", 2900)
        val r = l.finish(1, BuildRecord.DONE, 1_000 + 19 * 60_000)!!
        assertEquals("milly1", r.profileId); assertEquals("Milly", r.profileName)
        assertEquals(3300, r.peakMb); assertEquals("Decoding", r.stage); assertEquals(19 * 60_000L, r.durationMs)
        assertTrue(r.line().contains("for Milly")); assertTrue(r.line().contains("done")); assertTrue(r.line().contains("(19 min)")); assertTrue(r.line().contains("3.2 GB"))
    }

    @Test fun aRunCutOffByAKillShowsAsInterruptedThenResumes() {
        val l = log(); l.start(5, "m", "Milly", 0); l.progress(5, "Detailed shape 7/12", 3000)
        l.settle(isRunning = true, now = 10)                       // still running: untouched
        assertEquals(BuildRecord.RUNNING, l.byId(5)!!.result)
        l.settle(isRunning = false, now = 99)                      // process died: interrupted, at the stage it reached
        val r = l.byId(5)!!; assertEquals(BuildRecord.INTERRUPTED, r.result); assertEquals(99L, r.endedAt); assertEquals("Detailed shape 7/12", r.stage)
        assertTrue(r.line().contains("interrupted at Detailed shape 7/12"))
        l.resumed(5); assertEquals(BuildRecord.RUNNING, l.byId(5)!!.result); assertEquals(1, l.byId(5)!!.resumes); assertNull(l.byId(5)!!.endedAt)
        l.finish(5, BuildRecord.FAILED, 200, "out of memory")
        assertTrue(l.byId(5)!!.line().contains("failed at Detailed shape 7/12: out of memory"))
    }

    @Test fun keepsTheNewestRunsAndSurvivesADamagedFile() {
        val dir = Files.createTempDirectory("bl2").toFile(); val f = File(dir, "h.ck"); val l = BuildLog(f)
        for (i in 1..(BuildLog.KEEP + 5)) l.start(i.toLong(), "p", "P", i.toLong())
        val all = l.all(); assertEquals(BuildLog.KEEP, all.size); assertEquals(6L, all.first().id)
        f.writeBytes(f.readBytes().copyOf(20))                      // truncated: an empty history, not a crash
        assertTrue(l.all().isEmpty())
    }

    @Test fun eventsArePersistedTrimmedAndRedacted() {
        val l = log()
        repeat(BuildLog.EVENT_LINES + 20) { l.event(it.toLong(), "p3d: step $it") }
        val ev = l.events(); assertEquals(BuildLog.EVENT_LINES, ev.size); assertTrue(ev.last().endsWith("p3d: step ${BuildLog.EVENT_LINES + 19}"))
        l.event(0, "p3d: photo content://media/external/images/123 from /storage/emulated/0/DCIM/me.jpg by a@b.com")
        val last = l.events().last()
        assertFalse(last.contains("DCIM")); assertFalse(last.contains("123")); assertFalse(last.contains("a@b.com"))
    }

    @Test fun exportKeepsOnlyRelevantAppEvents() {
        val ev = listOf("heard: call my mum", "p3d: Rough shape 1/8 at 30 s", "reply: hello sugar", "download qwen failed: timeout", "error: OOM in decoder", "llm: 42 tokens")
        assertEquals(listOf("p3d: Rough shape 1/8 at 30 s", "download qwen failed: timeout", "error: OOM in decoder"), BuildLog.exportableEvents(ev))
        val rep = BuildLog.report(listOf("app 0.1.3"), listOf(BuildRecord(1, "m", "Milly", 0, 60_000, "Decoding", BuildRecord.DONE)), null, listOf("x"), null, ev)
        assertTrue(rep.contains("for Milly")); assertFalse(rep.contains("call my mum")); assertFalse(rep.contains("hello sugar"))
    }

    @Test fun orphanDetection() {
        assertFalse(OrphanModel.isOrphan(null, null))                              // no model at all
        assertTrue(OrphanModel.isOrphan("abc", null))                              // made by an older version, never attached
        assertFalse(OrphanModel.isOrphan("abc", OrphanModel.attachedJson("abc", "milly", 1)))
        assertTrue(OrphanModel.isOrphan("def", OrphanModel.attachedJson("abc", "milly", 1)))   // a newer model than the attached one
        assertTrue(OrphanModel.isOrphan("abc", "{broken"))
    }
}
