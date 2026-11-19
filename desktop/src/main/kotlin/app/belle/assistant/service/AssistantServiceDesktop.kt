package app.belle.assistant.service

import android.content.Context
import app.belle.assistant.Prefs
import app.belle.assistant.speech.SAMPLE_RATE
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Desktop twin of the phone's microphone foreground service: one recording thread (16 kHz mono through Java Sound,
 * i.e. the Windows default recording device) feeding [AssistantRuntime.onAudio], exactly what the phone service does.
 * It lives as long as the app process (the window can be closed to the tray; it keeps listening). If the microphone
 * is missing or taken, it says so in the status and retries every 5 s.
 */
class AssistantService {
    companion object {
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"; const val ACTION_LISTEN = "listen"; const val ACTION_END = "end"
        const val ACTION_RELOAD = "reload"; const val ACTION_TEXT = "text"; const val ACTION_AVATAR = "avatar"; const val ACTION_REARM = "rearm"
        @Volatile var inForeground = false; private set
        @Volatile private var running = false
        @Volatile private var gen = 0
        @Volatile var micName: String = ""; private set
        @Volatile var micError: String? = null; private set

        fun start(ctx: Context, action: String = ACTION_START, from: String? = null) {
            if (action == ACTION_STOP) { Prefs(ctx).serviceWanted = false; stop(); return }
            Prefs(ctx).serviceWanted = true
            AssistantRuntime.init(ctx)
            synchronized(this) {
                if (!running) {
                    running = true; inForeground = true
                    AssistantRuntime.load(ctx)
                    startMic()
                }
            }
            when (action) {
                ACTION_LISTEN -> AssistantRuntime.listenNow(ctx)
                ACTION_END -> AssistantRuntime.endSession(ctx, "End button")
                ACTION_RELOAD -> AssistantRuntime.load(ctx, reloadLlm = true)
            }
        }

        fun stop() = synchronized(this) {
            if (!running) return
            running = false; inForeground = false; gen++
            AssistantRuntime.stop()
        }

        private fun startMic() {
            val my = ++gen
            Thread({
                val fmt = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
                while (running && my == gen) {
                    val line: TargetDataLine? = try { AudioSystem.getTargetDataLine(fmt).apply { open(fmt, SAMPLE_RATE / 5 * 2 * 2) } } catch (e: Exception) { null }
                    if (line == null) {
                        if (micError == null) AssistantRuntime.event("mic: no microphone available, retrying every 5 s")
                        micError = "No microphone found (or another app has it). Plug one in or pick it as the default recording device in Windows Sound settings."
                        AssistantRuntime.setMicSilenced(true); Thread.sleep(5000); continue
                    }
                    micError = null; micName = line.lineInfo.toString()
                    line.start()
                    AssistantRuntime.setMicSilenced(false)
                    AssistantRuntime.event("mic: recording")
                    val bytes = ByteArray(1600 * 2)
                    while (running && my == gen) {
                        val n = line.read(bytes, 0, bytes.size)
                        if (n <= 0) { AssistantRuntime.event("mic: read error, restarting"); break }
                        val f = FloatArray(n / 2) { i -> ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
                        AssistantRuntime.Diag.onChunk(f)
                        AssistantRuntime.onAudio(f)
                    }
                    runCatching { line.stop(); line.close() }
                }
            }, "belle-mic").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        }
    }
}
