package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StudioFlowTest {
    private val none = StudioFlow.Facts(false, false, false, false, false, false)
    private fun states(s: StudioState, f: StudioFlow.Facts) = StudioFlow.steps(s, f).map { it.state }
    private val D = StudioFlow.St.DONE; private val N = StudioFlow.St.NEXT; private val W = StudioFlow.St.WAITING

    @Test fun walksTheSteps() {
        assertEquals(listOf(N, W, W, W, W), states(StudioState(), none))
        val s1 = StudioState(profileId = "p"); val f1 = none.copy(profileExists = true)
        assertEquals(listOf(D, N, W, W, W), states(s1, f1))
        val s2 = s1.copy(picture = "picture.png"); val f2 = f1.copy(pictureExists = true)
        assertEquals(listOf(D, D, N, W, W), states(s2, f2))
        val s3 = s2.copy(kind = "3d")
        assertEquals(listOf(D, D, D, N, W), states(s3, f2))
        assertEquals("downloading now", StudioFlow.steps(s3, f2.copy(toolsInstalling = true))[3].why)
        val f4 = f2.copy(toolsReady = true)
        assertEquals(listOf(D, D, D, D, N), states(s3, f4))
        assertEquals(5, StudioFlow.current(StudioFlow.steps(s3, f4)))
        // finished only once the avatar is really assigned (after the final atomic commit)
        assertEquals(N, states(s3.copy(finished = true), f4).last())
        assertEquals(listOf(D, D, D, D, D), states(s3.copy(finished = true), f4.copy(avatarAssigned = true)))
    }

    @Test fun missingFilesReopenEarlierSteps() {
        // the picture was deleted, or the assistant removed: the step shows as to-do again, never as done
        val s = StudioState("p", "picture.png", "2d", finished = true)
        assertEquals(listOf(D, N, W, W, W), states(s, StudioFlow.Facts(true, false, true, false, false, true)))
        assertEquals(listOf(N, W, W, W, W), states(s, StudioFlow.Facts(false, true, true, false, false, true)))
    }

    @Test fun stateResumesAndSurvivesADamagedSave() {
        val st = CheckpointStore(Files.createTempDirectory("studio").toFile())
        assertEquals(StudioState(), StudioState.load(st))
        val a = StudioState("p1", "picture.png", "3d", buildStarted = true, step = 4, startedAt = 123)
        StudioState.save(st, a); assertEquals(a, StudioState.load(st))
        val b = a.copy(finished = true); StudioState.save(st, b); assertEquals(b, StudioState.load(st))
        // the newest save is cut short: the one before is used
        val newest = st.dir.listFiles()!!.maxByOrNull { f -> AtomicFiles.readSealed(f)?.let { String(it.copyOfRange(4, it.size)).contains("\"finished\":true") } == true && true }!!
        newest.writeBytes(newest.readBytes().copyOf(newest.length().toInt() / 2))
        assertEquals(a, StudioState.load(st))
        // nonsense in the file → a fresh start, not a crash
        assertEquals(StudioState(kind = null), StudioState.parse("{\"kind\":\"4d\"}"))
    }
}
