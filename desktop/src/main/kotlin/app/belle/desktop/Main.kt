package app.belle.desktop

import app.belle.assistant.core.Platform
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import app.belle.assistant.service.Reminders
import app.belle.desktop.accel.Backend
import app.belle.desktop.accel.BackendChoice
import app.belle.desktop.accel.HardwareProbe
import app.belle.desktop.ui.DesktopApp
import app.belle.desktop.ui.Notify
import app.belle.desktop.ui.SingleInstance

/**
 * The Windows program: the desktop widget (always-on-top avatar window), the tray icon and the settings window,
 * around the same assistant runtime, settings, memory, books, reminders and 3D pipeline the phone app uses.
 */
fun main(args: Array<String>) {
    if (args.contains("--version")) { println("Milky Moo's Assistant ${BuildInfo.VERSION} (${BuildInfo.GIT_SHA})"); return }
    args.indexOf("--selftest").takeIf { it >= 0 }?.let { i -> kotlin.system.exitProcess(SelfTest.run(java.io.File(args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") } ?: "selftest.txt"), gpu = "--gpu" in args)) }
    Platform.desktop = true
    Thread.setDefaultUncaughtExceptionHandler { t, e -> DesktopLog.line('E', "Crash", "uncaught on ${t.name}", e) }
    if (!SingleInstance.claim()) return   // already running: the other copy shows its widget
    android.util.Log.i("Main", "start ${BuildInfo.VERSION} (${BuildInfo.GIT_SHA}) on ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    startRuntime()
    app.belle.desktop.ui.DesktopPages.register()
    DesktopApp.run(openSettings = args.contains("--settings") || (!DesktopPrefs.welcomed && !args.contains("--tray")))
}

/** Natives for the chosen backends, then the shared runtime; slow work (reminders, resuming builds, the microphone) off the UI thread. */
fun startRuntime() {
    val ctx = DesktopContext
    val wantPhoto = DesktopPrefs.photoBackend; val wantChat = DesktopPrefs.chatBackend
    val hw = HardwareProbe.get()
    val photo = BackendChoice.photo3D(wantPhoto, hw, RuntimePacks::photoReady)
    val chat = BackendChoice.chat(wantChat, hw, RuntimePacks::chatReady)
    android.util.Log.i("Main", "hardware: ${hw.summary()} · photo→3D ${photo.backend.label} (${photo.why}) · chat ${chat.backend.label} (${chat.why})")
    Natives.install(photo.backend, chat.backend)
    // Photo → 3D plans (steps, time left) are fitted to the backend in use; cautious speed-ups until a first run is timed
    app.belle.assistant.threed.Pixal3DJob.accel = {
        // after a GPU provider failed and the run fell back, it is the CPU that is being timed
        val b = if (app.belle.assistant.threed.Ort.provider == "cpu") Backend.CPU else Natives.photoBackend
        app.belle.assistant.threed.Pixal3DJob.Accel(b.id, b.label, when (b) { Backend.CUDA -> 8.0; Backend.DIRECTML -> 3.0; Backend.OPENVINO -> 2.0; else -> 1.0 })
    }
    Platform.describe = { "PC: ${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${hw.summary()} · 3D on ${photo.backend.label}, chat on ${chat.backend.label}" }
    Reminders.onFire = { r, late ->
        val text = if (late) "${r.text} (was due ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(r.at))})" else r.text
        Notify.show(if (r.alarm) text else "Reminder", text)
        AssistantRuntime.event("reminder fired: $text")
        if (!r.alarm) runCatching { AssistantRuntime.say("Reminder: $text") } else app.belle.desktop.platform.Chimes.alarm()
    }
    AssistantRuntime.init(ctx)
    Thread({
        runCatching { Reminders.rescheduleAll(ctx) }
        runCatching { app.belle.assistant.models.DownloadService.resumeAll(ctx) }
        runCatching { RuntimePacks.resumeAll() }
        runCatching { app.belle.assistant.threed.Pixal3DService.resumeIfPending(ctx) }
        // listening was on when the program last closed: start the microphone again
        if (app.belle.assistant.Prefs(ctx).serviceWanted) AssistantService.start(ctx, from = "startup")
    }, "startup").apply { isDaemon = true; start() }
}
