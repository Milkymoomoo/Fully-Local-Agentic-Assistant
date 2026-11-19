package app.belle.assistant.threed

import android.content.Context
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.desktop.platform.KeepAwake
import app.belle.desktop.ui.Notify

/**
 * Desktop twin of the phone's photo → 3D foreground service: keeps the PC awake for the whole run (the phone's
 * wake lock), shows the progress in the tray tooltip (the phone's ongoing notification), posts the result as a
 * Windows notification, and resumes an unfinished run from its checkpoint when the program starts again.
 */
class Pixal3DService {
    companion object {
        @Volatile private var watcher: Thread? = null

        fun start(ctx: Context) = synchronized(this) {
            KeepAwake.acquire("pixal3d")
            if (watcher?.isAlive == true) return
            val since = System.currentTimeMillis()
            watcher = Thread({
                try {
                    while (Pixal3DJob.running || System.currentTimeMillis() - since < 8000) {
                        val (what, f) = Avatar3DFiles.progress ?: ("Starting" to 0f)
                        Notify.progress("Making a 3D model · ${Pixal3DJob.etaText ?: "estimating time left…"}", "$what · ${(f * 100).toInt()}% · on this PC, nothing is uploaded")
                        Thread.sleep(2000)
                    }
                } catch (_: InterruptedException) {
                } finally { Notify.progress(null, null); KeepAwake.release("pixal3d") }
            }, "pixal3d-watch").apply { isDaemon = true; start() }
        }

        /** "Milly's 3D avatar is ready" (click: the 3D page), or the failure and its reason. */
        fun notifyResult(ctx: Context, ok: Boolean, profileId: String, name: String, error: String?) = runCatching {
            val possessive = if (name.endsWith("s")) "$name'" else "$name's"
            Notify.show(if (ok) "$possessive 3D avatar is ready" else "$possessive 3D avatar couldn't be made",
                if (ok) "Click to see it, make $name active and show it on the widget." else "Reason: ${error ?: "unknown error"}. Saved progress continues the next time the program opens.",
                error = !ok, page = "avatar3d", p3dProfile = profileId)
        }

        /** Continue an unfinished run (program opened again, or the PC restarted). Safe to call any time. */
        fun resumeIfPending(ctx: Context): Boolean {
            if (Pixal3DJob.running || !Pixal3DJob.canResume(ctx)) return false
            start(ctx)
            Thread({ Pixal3DJob.resume(ctx.applicationContext) }, "pixal3d-resume").start()
            return true
        }
    }
}
