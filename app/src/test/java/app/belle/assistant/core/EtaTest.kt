package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaTest {
    @Test fun formatsPlainWordsRoundedUpSensibly() {
        assertEquals("estimating time left…", Eta.format(null))
        assertEquals("<1 min left", Eta.format(12.0))
        assertEquals("about 2 min left", Eta.format(61.0))
        assertEquals("about 9 min left", Eta.format(530.0))
        assertEquals("about 15 min left", Eta.format(11 * 60.0))          // 5 min steps from 10 min
        assertEquals("about 25 min left", Eta.format(24 * 60.0 + 1))
        assertEquals("about 1 h left", Eta.format(3540.0))
        assertEquals("about 1 h 15 min left", Eta.format(4320.0))
        assertEquals("about 2 h 10 min left", Eta.format(2 * 3600.0 + 61))   // 10 min steps from 2 h
    }

    @Test fun smoothing() { val s = Eta.Smooth(0.5); s.add(10.0); assertEquals(15.0, s.add(20.0), 1e-9) }

    private fun times() = DeviceTimes("dev", 10.0, 20.0, 30.0, 60.0, 40.0, runs = 1, measuredMidRun = true)

    @Test fun pixalCountsDownThroughStages() {
        var now = 0L; val e = Pixal3DEta(8, 12, times()) { now }
        // whole run up front: (prepare 30 + 8×10 + 12×20 + 60 + 40) × the 10 % margin
        assertEquals(450.0 * Pixal3DEta.MARGIN, e.remainingSec()!!, 1e-6)
        now = 30_000; e.onProgress("Rough shape 1/8", 0.15f)
        now = 40_000; e.onProgress("Rough shape 2/8", 0.2f)               // 10 s a step, as expected
        assertEquals((6 * 10.0 + 240 + 100) * Pixal3DEta.MARGIN, e.remainingSec()!!, 1.0)
        e.onProgress("Decoding", 0.9f); assertEquals(Pixal3DEta.Stage.DECODE, e.stage)
        e.onProgress("Done", 1f); assertEquals(0.0, e.remainingSec()!!, 0.0)
    }

    /**
     * Real-phone trace (0.1.3, OUKITEL WP100 TITAN, official workflow 12 rough + 20 detailed steps). The app said "30 min" at
     * rough 3/12 (6:46 ET) and "24 min", then "28 min", at rough 5/12 (6:57 ET): about 5.5 min per rough step, so the
     * old estimate was far too short and jumped up and down. The phone had no saved times: the first guess is the
     * desktop box's speed scaled by [Eta.PHONE_VS_DESKTOP].
     */
    @Test fun wp100FirstTraceIsConservativeAndCountsDownSmoothly() {
        val desktopRough = 19.9 * 21 / 12; val desktopDetailed = 6.6 * 35 / 20          // desktop s/step for 12 + 20 steps
        val k = Eta.PHONE_VS_DESKTOP
        val firstGuess = DeviceTimes.load(null, "OUKITEL WP100 TITAN", k * desktopRough, k * desktopDetailed)
        var now = 0L; val e = Pixal3DEta(12, 20, firstGuess) { now }
        // the true phone: 330 s per rough step (±10 %), then about 3 min per detailed step (the second WP100 trace)
        val roughSec = listOf(330, 300, 360, 315, 345, 330, 300, 360, 330, 315, 345, 330)
        val trueDetailed = 180.0                                                     // measured on the WP100 later (next test)
        fun trueLeft(roughDone: Int, inStep: Double) = roughSec.drop(roughDone).sum() - inStep + 20 * trueDetailed + (90.0 + 40.0) * (trueDetailed / 13.2)

        now = 25_000; e.onProgress("Rough shape", 0.10f)                       // prepare took 25 s
        var shownBefore: Double? = null; var ups = 0
        var t = now
        for ((i, sec) in roughSec.withIndex()) {
            // look at the screen every 30 s during the step
            var inStep = 0.0
            while (inStep + 30 < sec) {
                inStep += 30; now = t + (inStep * 1000).toLong()
                val shown = e.displaySec()!!
                if (shownBefore != null && shown > shownBefore + 1e-6) { ups++; assertTrue("small jump up at rough ${i + 1}: $shownBefore → $shown", shown - shownBefore > 180) }
                if (i >= 2) assertTrue("too short at rough $i/12 +${inStep}s: ${shown / 60} min vs ${trueLeft(i, inStep) / 60} min true",
                    shown >= 0.95 * trueLeft(i, inStep))
                shownBefore = shown
            }
            t += sec * 1000L; now = t; e.onProgress("Rough shape ${i + 1}/12", 0.10f + 0.4f * (i + 1) / 12)
            if (i + 1 == 3) {
                // where the phone said "30 min": now about 9 × 5.5 min of rough steps alone are left, plus the detailed ones
                val raw = e.remainingSec()!!; assertTrue("rough 3/12: ${raw / 60} min", raw > 9 * 300 + 20 * trueDetailed * 0.9)
                assertTrue("not wildly long: ${raw / 60} min", raw < 1.6 * trueLeft(3, 0.0))
            }
            if (i + 1 == 5) assertTrue(e.text(), e.remainingSec()!! > 7 * 300)
        }
        assertTrue("re-anchored upward too often: $ups", ups <= 2)
    }

    /**
     * The second WP100 trace (0.1.3, official workflow 12 + 20): the 12 rough steps took about 43 min (3/12 at 6:46,
     * 5/12 at 6:57), detailed began about 7:21 and showed 2/20 at 7:27 with "43 min" left, while about 18 × 3 min of
     * detailed steps alone were left. Times are seconds from the start (6:38).
     */
    @Test fun wp100SecondTraceThroughDetailedStage() {
        val desktopRough = 19.9 * 21 / 12; val desktopDetailed = 6.6 * 35 / 20
        val k = Eta.PHONE_VS_DESKTOP
        var now = 0L; val e = Pixal3DEta(12, 20, DeviceTimes.load(null, "OUKITEL WP100 TITAN", k * desktopRough, k * desktopDetailed)) { now }
        val rough = listOf(160, 160, 160, 330, 330, 205, 205, 205, 205, 205, 205, 205)       // 3/12 at 6:46, 5/12 at 6:57
        val detailed = List(20) { if (it % 2 == 0) 170 else 190 }                           // about 3 min per step
        e.onProgress("Rough shape", 0.10f)
        var t = 0L
        for ((i, sec) in rough.withIndex()) { t += sec * 1000L; now = t; e.onProgress("Rough shape ${i + 1}/12", 0.10f + 0.4f * (i + 1) / 12) }
        assertTrue("rough ends about 7:21", t / 60_000 in 42..43)
        e.onProgress("Detailed shape (21000 voxels)", 0.50f)
        val fixedLeft = (90.0 + 40.0) * k * (180.0 / (k * desktopDetailed))                  // decode + texture, as slow as detailed
        // right after the rough stage: the detailed stage isn't priced at the box's 1/3 of a rough step
        val atStart = e.remainingSec()!!
        println("WP100 detailed start: raw ${(atStart / 60).toInt()} min")
        assertTrue("start of detailed: ${atStart / 60} min, the 20 steps alone take ${20 * 3} min", atStart >= 0.8 * 20 * 180)
        var shownBefore = Double.MAX_VALUE; var ups = 0
        for ((i, sec) in detailed.withIndex()) {
            var inStep = 0
            while (inStep + 30 < sec) {
                inStep += 30; now = t + inStep * 1000L
                val shown = e.displaySec()!!
                if (shown > shownBefore + 1e-6) { ups++; assertTrue("small jump up at detailed ${i + 1}: $shownBefore → $shown", shown - shownBefore > 180) }
                shownBefore = shown
            }
            t += sec * 1000L; now = t; e.onProgress("Detailed shape ${i + 1}/20", 0.50f + 0.35f * (i + 1) / 20)
            if (i + 1 == 2) {
                // 7:27, "2/20": the old build said 43 min; 18 steps of about 3 min are left, then decode and texture
                val trueLeft = detailed.drop(2).sum() + fixedLeft
                val raw = e.remainingSec()!!; val shown = e.displaySec()!!
                println("WP100 2/20: shown ${e.text()} (raw ${(raw / 60).toInt()} min, true ${(trueLeft / 60).toInt()} min; 0.1.3 said 43 min)")
                assertTrue("2/20 raw ${raw / 60} min vs true ${trueLeft / 60}", raw >= 0.95 * trueLeft && raw <= 1.5 * trueLeft)
                assertTrue("2/20 shown ${shown / 60} min must be more than the old 43 min", shown > 43 * 60 && shown >= 0.9 * trueLeft)
                shownBefore = shown
            }
        }
        assertTrue("re-anchored upward too often: $ups", ups <= 2)
        // the next run on this phone starts from this one's times, detailed included
        val learned = e.learned(DeviceTimes.load(null, "OUKITEL WP100 TITAN", k * desktopRough, k * desktopDetailed), finished = false)
        assertEquals(180.0, learned.shapeStepSec!!, 15.0)
    }

    /**
     * The full OUKITEL WP100 TITAN build log (0.1.3, 12 + 20 steps, 3,843 voxels), step by step, seconds from the start:
     * rough about 245 s/step (the last 3: about 131 s), detailed about 232 s/step after a 304 s first step (the last 5:
     * about 118 s), then "Building the surface" ran 186 s before the out-of-memory error (so the surface takes > 3 min).
     * Leaning long is right: the phone sped up late in each stage, which no estimate could know in advance.
     */
    @Test fun wp100FullLogTrace() {
        val roughAt = listOf(279, 509, 748, 993, 1242, 1494, 1748, 2008, 2269, 2400, 2532, 2665)
        val detailedStart = 2675
        val detailedAt = listOf(2979, 3208, 3438, 3668, 3899, 4131, 4364, 4597, 4831, 5065, 5300, 5535, 5770, 6005, 6240, 6358, 6476, 6594, 6712, 6829)
        val surfaceStart = 6830; val surfaceMin = 186
        val k = Eta.PHONE_VS_DESKTOP; val desktopRough = 19.9 * 21 / 12; val desktopDetailed = 6.6 * 35 / 20
        var now = 0L
        val e = Pixal3DEta(12, 20, DeviceTimes.load(null, "OUKITEL WP100 TITAN", k * desktopRough, k * desktopDetailed)) { now }
        now = 58_000; e.onProgress("Rough shape", 0.10f)
        val endOfSurface = surfaceStart + surfaceMin   // a lower bound for the true end (surface, paint and rig still follow)
        var shownBefore = Double.MAX_VALUE; var smallUps = 0
        fun watch(from: Int, to: Int) { var t = from + 30; while (t < to) { now = t * 1000L; val sh = e.displaySec()!!
            if (sh > shownBefore + 1e-6 && sh - shownBefore < 180) smallUps++; shownBefore = sh; t += 30 } }
        var prev = 58
        for ((i, at) in roughAt.withIndex()) {
            watch(prev, at); now = at * 1000L; e.onProgress("Rough shape ${i + 1}/12", 0.10f + 0.4f * (i + 1) / 12); prev = at
            if (i + 1 in 3..9) { val raw = e.remainingSec()!!
                assertTrue("rough ${i + 1}/12: ${(raw / 60).toInt()} min left vs at least ${(endOfSurface - at) / 60} min", raw >= 0.95 * (endOfSurface - at)) }
        }
        watch(prev, detailedStart); now = detailedStart * 1000L; e.onProgress("Detailed shape (3843 voxels)", 0.50f); prev = detailedStart
        for ((i, at) in detailedAt.withIndex()) {
            watch(prev, at); now = at * 1000L; e.onProgress("Detailed shape ${i + 1}/20", 0.50f + 0.35f * (i + 1) / 20); prev = at
            if (i + 1 in 2..15) { val raw = e.remainingSec()!!
                assertTrue("detailed ${i + 1}/20: ${(raw / 60).toInt()} min left vs at least ${(endOfSurface - at) / 60} min", raw >= 0.95 * (endOfSurface - at)) }
        }
        now = surfaceStart * 1000L; e.onProgress("Building the surface", 0.86f)
        val atSurface = e.remainingSec()!!
        println("WP100 full log: at the surface ${e.text()} (raw ${(atSurface / 60).toInt()} min; the surface alone ran > 3 min)")
        assertTrue("surface + paint + rig estimated at ${atSurface.toInt()} s, the surface alone took more than $surfaceMin s", atSurface >= surfaceMin)
        assertEquals("never creeps up by a few minutes", 0, smallUps)
        val learned = e.learned(DeviceTimes.load(null, "OUKITEL WP100 TITAN", k * desktopRough, k * desktopDetailed), finished = false)
        assertTrue("learned rough ${learned.structureStepSec} s/step", learned.structureStepSec!! in 110.0..260.0)
        assertTrue("learned detailed ${learned.shapeStepSec} s/step", learned.shapeStepSec!! in 110.0..250.0)
    }

    @Test fun stepCountsComeFromWhatThePipelineReports() {
        // built for 8 + 12 but the pipeline runs the imported workflow's 12 + 20: the reported totals win
        var now = 0L; val e = Pixal3DEta(8, 12, times()) { now }
        e.onProgress("Rough shape", 0.10f)
        now += 10_000; e.onProgress("Rough shape 1/12", 0.13f)
        assertEquals(12, e.steps(Pixal3DEta.Stage.STRUCTURE))
        now += 10_000; e.onProgress("Detailed shape 1/20", 0.52f)
        assertEquals(20, e.steps(Pixal3DEta.Stage.SHAPE))
        val p = ProgressText.split("Detailed shape 2/20")
        assertEquals("Detailed shape", p.stage); assertEquals(2, p.step); assertEquals(20, p.steps)
    }

    @Test fun displayNeverWobblesUpByAMinute() {
        var now = 0L; val e = Pixal3DEta(10, 10, times()) { now }
        e.onProgress("Rough shape", 0.10f)
        var last = e.displaySec()!!
        for (i in 1..10) {
            // alternate fast and slow steps (8 s / 12 s around the expected 10 s)
            now += if (i % 2 == 0) 12_000 else 8_000; e.onProgress("Rough shape $i/10", 0.1f + 0.04f * i)
            val d = e.displaySec()!!; assertTrue("went up $last → $d at step $i", d <= last + 1e-6); last = d
        }
    }

    @Test fun slowerThanSavedHistoryReAnchorsUpOnceAndScalesLaterStages() {
        var now = 0L; val e = Pixal3DEta(8, 12, times()) { now }
        e.onProgress("Rough shape", 0.10f); val before = e.displaySec()!!
        now += 30_000; e.onProgress("Rough shape 1/8", 0.15f); now += 30_000; e.onProgress("Rough shape 2/8", 0.2f)   // 3× slower than saved
        assertEquals(3.0, e.speedFactor, 0.01)
        val after = e.displaySec()!!
        assertTrue("$before → $after", after > before)                                  // a big change: re-anchored up
        assertTrue(after >= (6 * 30.0 + 12 * 60 + 300) * 0.99)                          // detailed + decode + texture scaled ×3 too
    }

    @Test fun learnsPerDeviceEvenFromAnUnfinishedRunAndIgnoresOtherDevices() {
        val guess = DeviceTimes("dev", 10.0, 20.0, 30.0, 60.0, 40.0)
        var now = 0L; val e = Pixal3DEta(2, 2, guess) { now }
        e.onProgress("Rough shape", 0.10f)
        now = 20_000; e.onProgress("Rough shape 1/2", 0.2f); now = 40_000; e.onProgress("Rough shape 2/2", 0.4f)
        val mid = e.learned(guess, finished = false)
        assertEquals(20.0, mid.structureStepSec!!, 1e-6); assertEquals(0, mid.runs); assertTrue(mid.measuredMidRun)
        assertEquals(40.0, mid.shapeStepSec!!, 1e-6)                                   // not timed yet: the guess scaled ×2
        val back = DeviceTimes.load(mid.json(), "dev", 1.0, 1.0); assertEquals(20.0, back.structureStepSec!!, 1e-9); assertTrue(back.measuredMidRun)
        val other = DeviceTimes.load(mid.json(), "another phone", 3.0, 13.2); assertEquals(3.0, other.structureStepSec!!, 0.0); assertEquals(0, other.runs)
        assertEquals(1, e.learned(guess).runs)
    }

    @Test fun unknownSpeedSaysEstimating() {
        val e = Pixal3DEta(8, 12, DeviceTimes("d", null, null, null, null, null)) { 0L }
        assertNull(e.remainingSec()); assertEquals("estimating time left…", e.text())
    }

    @Test fun transferUsesSmoothedSpeedAndQueuePosition() {
        var now = 0L; val t = TransferEta(null) { now }
        assertNull(t.remainingSec(100))
        t.sample(0); now = 500; t.sample(1_000_000)                        // under a second: ignored
        assertNull(t.bytesPerSec)
        now = 1000; t.sample(1_000_000); assertEquals(1_000_000.0, t.bytesPerSec!!, 1e-6)
        assertEquals(10.0, t.remainingSec(10_000_000)!!, 1e-6)
        val q = t.queue(listOf(5_000_000, 5_000_000, 20_000_000))          // 3rd in line waits for the two ahead
        assertEquals(listOf(5.0, 10.0, 30.0), q.map { it!! })
        now = 2000; t.sample(1_500_000)                                    // slower now → smoothed toward 0.5 MB/s
        assertEquals(900_000.0, t.bytesPerSec!!, 1e-6)
    }
}
