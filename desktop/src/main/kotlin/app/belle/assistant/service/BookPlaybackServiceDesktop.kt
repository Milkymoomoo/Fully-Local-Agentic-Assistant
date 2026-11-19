package app.belle.assistant.service

import android.content.Context

/**
 * Desktop twin of the phone's media-playback service for "Read to me": the PC is kept awake while a book plays
 * (a book can run for hours), and the tray menu shows the play/pause/stop controls.
 */
class BookPlaybackService {
    companion object {
        @Volatile var showing = false; private set
        fun ensure(ctx: Context) { if (!showing) { showing = true; app.belle.desktop.platform.KeepAwake.acquire("book") }; app.belle.assistant.ui.AssistantWidget.onStateChanged(ctx) }
        fun dismiss(ctx: Context) { if (showing) { showing = false; app.belle.desktop.platform.KeepAwake.release("book") }; app.belle.assistant.ui.AssistantWidget.onStateChanged(ctx) }
    }
}
