package app.belle.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import app.belle.assistant.Prefs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The assistant's sound slots. Each can be the built-in generated chime, one of the phone's own sounds
 * (RingtoneManager picker → "ring:<uri>") or a file the user imported (copied into app storage → "file:<name>").
 * Custom sounds play through MediaPlayer (mp3, ogg, wav, m4a/aac, flac, opus… whatever the phone decodes) on the
 * assistant stream at the voice volume, cut off after [MAX_MS]. If one can't be played, the built-in chime plays.
 */
object Sounds {
    data class Slot(val id: String, val title: String, val builtIn: () -> FloatArray)
    val SLOTS = listOf(
        Slot("start", "When I start listening") { Chime.samples() },
        Slot("follow", "When I keep listening after a reply") { Chime.followSamples() },
        Slot("stop", "When I stop listening") { Chime.stopSamples() },
        Slot("sleep", "Goodnight (sleep word / End)") { Chime.sleepSamples() },
    )
    const val MAX_MS = 8000L
    val EXTENSIONS = setOf("mp3", "ogg", "oga", "opus", "wav", "m4a", "aac", "mp4", "3gp", "flac", "amr", "mid", "midi", "webm", "mka")

    fun dir(ctx: Context) = File(ctx.filesDir, "sounds").apply { mkdirs() }
    fun slot(id: String) = SLOTS.first { it.id == id }

    /** Short label for the settings row. */
    fun describe(ctx: Context, v: String): String = when {
        v.startsWith("ring:") -> runCatching {
            android.media.RingtoneManager.getRingtone(ctx, Uri.parse(v.removePrefix("ring:")))?.getTitle(ctx)
        }.getOrNull() ?: "Phone sound"
        v.startsWith("file:") -> v.removePrefix("file:").substringAfter('_').substringAfter('_')
        else -> "Built-in chime"
    }

    /** Copies a picked file into app storage for [slot] of profile [profile]; returns the pref value. */
    fun import(ctx: Context, profile: String, slot: String, uri: Uri, displayName: String): Result<String> = runCatching {
        val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it in EXTENSIONS }
            ?: ctx.contentResolver.getType(uri)?.let { t -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(t) }?.lowercase()
            ?: error("Not a sound file")
        val safe = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)
        dir(ctx).listFiles { f -> f.name.startsWith("${profile}_${slot}_") }?.forEach { it.delete() }
        val out = File(dir(ctx), "${profile}_${slot}_$safe.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        require(out.length() in 1..(30L shl 20)) { out.delete(); "File is empty or larger than 30 MB" }
        "file:${out.name}"
    }

    /** Removes the imported file (if any) and returns to the built-in chime. */
    fun reset(ctx: Context, prefs: Prefs, slot: String) {
        val v = prefs.sound(slot)
        if (v.startsWith("file:")) File(dir(ctx), v.removePrefix("file:")).delete()
        prefs.setSound(slot, "")
    }

    /** Plays the slot (blocking, call off the main thread). */
    fun play(ctx: Context, prefs: Prefs, slot: String) = play(ctx, prefs.sound(slot), slot, prefs.voiceVolume)

    fun play(ctx: Context, v: String, slot: String, volume: Float) {
        val uri: Uri? = when {
            v.startsWith("ring:") -> Uri.parse(v.removePrefix("ring:"))
            v.startsWith("file:") -> File(dir(ctx), v.removePrefix("file:")).takeIf { it.isFile }?.let { Uri.fromFile(it) }
            else -> null
        }
        if (uri == null || !playMedia(ctx, uri, volume)) AudioOut.play(slot(slot).builtIn(), 22050)
    }

    private fun playMedia(ctx: Context, uri: Uri, volume: Float): Boolean {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            mp.setDataSource(ctx, uri)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            val v = volume.coerceIn(0f, 1f); mp.setVolume(v, v)
            mp.start()
            done.await(MAX_MS, TimeUnit.MILLISECONDS)
            true
        } catch (e: Exception) {
            android.util.Log.w("Belle", "sound $uri: $e"); false
        } finally { runCatching { mp.stop() }; mp.release() }
    }
}
