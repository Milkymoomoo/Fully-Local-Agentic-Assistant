package app.belle.assistant.models

import android.content.Context

/**
 * Desktop twin of Android's download foreground service: while any download runs, the PC is kept from sleeping
 * ([app.belle.desktop.platform.KeepAwake]) and the tray shows that work is going on.
 */
object DownloadService {
    private var active = 0
    @Synchronized fun started(ctx: Context) { if (active++ == 0) app.belle.desktop.platform.KeepAwake.acquire("downloads") }
    @Synchronized fun finished(ctx: Context) { if (active > 0 && --active == 0) app.belle.desktop.platform.KeepAwake.release("downloads") }
    /** At start: carry on with downloads (models and the 3D set) that were running when the program closed. */
    fun resumeAll(ctx: Context): Boolean {
        val a = runCatching { Downloads.resumeWanted(ctx) > 0 }.getOrDefault(false)
        val b = runCatching { app.belle.assistant.threed.Pixal3DInstall.resumeIfWanted(ctx) }.getOrDefault(false)
        return a || b
    }
}
