package app.belle.assistant.ui

import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * The Downloads page opens and scrolls without blocking the main thread, even on a first launch while the built-in
 * avatars are being prepared in the background:
 *  1. no disk access on the main thread while it opens and scrolls (StrictMode; the page loads "installed?",
 *     RAM and sizes on a background thread);
 *  2. every main-thread message (a frame, a click, a callback), timed with the looper's own begin/end lines, stays
 *     under [LIMIT_MS] on a phone. On an emulator without hardware acceleration everything runs 20–100× slower, so
 *     there the Downloads page may take at most [BASELINE_FACTOR] × as long as opening the plain Privacy page ( 0.1.4 took over 20 s there and was killed as not responding), and the
 *     absolute numbers are logged.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadStallTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()
    private val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.FINGERPRINT.contains("generic")

    private class Timing { var worst = 0L; val slow = mutableListOf<String>() }

    private fun timed(sc: ActivityScenario<MainActivity>, page: String, scroll: Boolean, disk: MutableList<String>?): Timing {
        val tm = Timing(); var t0 = 0L; var what = ""
        sc.onActivity { it.state.page = ""; it.state.tab = "home" }; SystemClock.sleep(3000)
        inst.runOnMainSync {
            Looper.getMainLooper().setMessageLogging { line ->
                if (line.startsWith(">>>")) { t0 = SystemClock.uptimeMillis(); what = line }
                else if (line.startsWith("<<<") && t0 > 0) { val d = SystemClock.uptimeMillis() - t0; if (d > tm.worst) tm.worst = d; if (d > LIMIT_MS) tm.slow += "$d ms: ${what.take(140)}" }
            }
            if (disk != null) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyListener(Executors.newSingleThreadExecutor()) { v -> synchronized(disk) { disk += (v.stackTrace.firstOrNull { it.className.startsWith("app.belle") }?.toString() ?: v.toString()) } }.build())
        }
        try {
            sc.onActivity { it.state.tab = "settings"; it.state.page = page }
            SystemClock.sleep(4000)
            if (scroll) { repeat(6) { shell("input swipe 540 1700 540 500 250"); SystemClock.sleep(800) }; repeat(3) { shell("input swipe 540 500 540 1700 250"); SystemClock.sleep(800) } }
            SystemClock.sleep(1500)
        } finally { inst.runOnMainSync { Looper.getMainLooper().setMessageLogging(null); if (disk != null) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) } }
        return tm
    }

    @Test fun downloadsPageOpensAndScrollsWithoutStalls() {
        val ctx = inst.targetContext
        val start = Intent(ctx, MainActivity::class.java).putExtra("tab", "home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(start).use { sc ->
            SystemClock.sleep(WARMUP_MS)   // app start itself is not what this test measures
            timed(sc, "privacy", false, null)                        // warm the settings screen once
            val base = timed(sc, "privacy", false, null)
            val disk = mutableListOf<String>()
            val dl = timed(sc, "getmodels", true, disk)
            Log.i("StallTest", "emulator=$emulator; Privacy page worst main-thread message ${base.worst} ms; Downloads page worst ${dl.worst} ms (${dl.slow.size} over $LIMIT_MS ms); main-thread disk access: ${disk.size}")
            dl.slow.forEach { Log.i("StallTest", it) }; disk.forEach { Log.i("StallTest", "disk: $it") }
            assertTrue("disk access on the main thread:\n" + disk.joinToString("\n"), disk.isEmpty())
            if (emulator) assertTrue("Downloads page ${dl.worst} ms vs the plain Privacy page ${base.worst} ms", dl.worst <= maxOf(LIMIT_MS, (base.worst * BASELINE_FACTOR).toLong()))
            else assertTrue("main thread blocked over $LIMIT_MS ms:\n" + dl.slow.joinToString("\n"), dl.slow.isEmpty())
        }
    }

    companion object { const val LIMIT_MS = 100L; const val WARMUP_MS = 8000L; const val BASELINE_FACTOR = 2.0 }
}
