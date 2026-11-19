package app.belle.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.belle.assistant.core.VoiceModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Play" next to every voice, also before it's downloaded:
 *  - installed: spoken live on this phone with the user's preview phrase;
 *  - Piper, not installed: the official sample clip from rhasspy/piper-samples (about 90 KB, fetched once, cached);
 *  - Kokoro: a short clip bundled in the app (made from the official model at build time), no internet.
 * The download respects Settings › Internet & search: with it off nothing is fetched; a sample already kept on the phone
 * still plays, otherwise the user is told the preview needs the internet (or to download the voice).
 */
object VoicePreview {
    private const val HOST = "rhasspy.github.io"
    @Volatile private var player: MediaPlayer? = null

    fun cacheFile(ctx: Context, v: VoiceModel) = File(File(ctx.cacheDir, "voice_samples").apply { mkdirs() }, v.id.substringBefore("-fp32").substringBefore("-int8") + ".mp3")
    fun cached(ctx: Context, v: VoiceModel) = v.sampleUrl.startsWith("asset:") || cacheFile(ctx, v).length() > 0

    enum class Plan { LIVE, BUNDLED, CACHED, DOWNLOAD, NEEDS_INTERNET, NO_SAMPLE }

    /** What ▶ does for a voice. Pure, so it's unit tested. */
    fun plan(installed: Boolean, sampleUrl: String, cached: Boolean, internetOn: Boolean): Plan = when {
        installed -> Plan.LIVE
        sampleUrl.isEmpty() -> Plan.NO_SAMPLE
        sampleUrl.startsWith("asset:") -> Plan.BUNDLED
        cached -> Plan.CACHED
        internetOn -> Plan.DOWNLOAD
        else -> Plan.NEEDS_INTERNET
    }

    const val NEEDS_INTERNET_MSG = "This sample needs the internet, which is off (Settings › Internet & search). Turn it on to hear it, or download the voice."

    /** Plays the preview; [onMessage] gets a short user-facing message (e.g. "needs internet"). Call off the main thread. */
    fun play(ctx: Context, v: VoiceModel, installed: Boolean, phrase: String, onMessage: (String) -> Unit) {
        stop()
        val url = v.sampleUrl
        when (plan(installed, url, cacheFile(ctx, v).length() > 0, app.belle.assistant.Prefs(ctx).internetEnabled)) {
            Plan.LIVE -> { app.belle.assistant.service.AssistantRuntime.previewVoice(ctx, v.dirName, phrase); return }
            Plan.NO_SAMPLE -> { onMessage("No sample for this voice yet. Download it to hear it."); return }
            Plan.NEEDS_INTERNET -> { onMessage(NEEDS_INTERNET_MSG); return }
            else -> {}
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if (url.startsWith("asset:")) {
                ctx.assets.openFd(url.removePrefix("asset:")).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            } else {
                val f = cacheFile(ctx, v)
                if (f.length() == 0L) {
                    val u = URL(url); require(u.host == HOST && u.protocol == "https")
                    onMessage("Getting the official sample (about 90 KB from rhasspy.github.io)…")
                    val c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000; instanceFollowRedirects = false }
                    try {
                        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                        val tmp = File(f.path + ".part"); c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        require(tmp.length() in 1..2_000_000); tmp.renameTo(f)
                    } finally { c.disconnect() }
                }
                mp.setDataSource(f.path)
            }
            mp.prepare(); val vol = app.belle.assistant.speech.AudioOut.volumeProvider().coerceIn(0f, 1f); mp.setVolume(vol, vol)
            player = mp; mp.setOnCompletionListener { it.release(); if (player === it) player = null }; mp.start()
        } catch (e: java.io.IOException) {
            mp.release(); onMessage("This preview needs the internet once (the sample is then kept on the phone). Check your connection.")
        } catch (e: Exception) {
            mp.release(); onMessage("Couldn't play the sample: ${e.message}")
        }
    }

    fun stop() { player?.let { runCatching { it.stop() }; it.release() }; player = null }
}
