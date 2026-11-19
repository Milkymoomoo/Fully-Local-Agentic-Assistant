package app.belle.assistant.actions

import android.content.Context
import app.belle.assistant.core.ActionResult
import app.belle.assistant.core.PhoneActions
import app.belle.desktop.Paths
import app.belle.desktop.platform.WinActions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Desktop twin of the phone actions (same class name, so the shared AssistantRuntime builds it unchanged). What a PC
 * can do is done for real; phone-only things (calls, texts, flashlight, camera) say so plainly instead of pretending.
 * Timers, alarms and reminders use the app's own saved reminders (they survive a restart), exactly like a phone with
 * no clock app. Notes use the same notes.txt format as the phone.
 */
class AndroidPhoneActions(private val ctx: Context) : PhoneActions {
    private fun ok(s: String) = ActionResult(true, s)
    private fun fail(s: String) = ActionResult(false, s)
    private val noPhone = "That needs a phone. On this PC I can do timers, reminders, alarms, notes, opening apps and websites, and media keys."

    override fun call(contact: String) = fail("I can't make calls from a PC. $noPhone")
    override fun sms(contact: String, message: String) = fail("I can't send texts from a PC. $noPhone")
    override fun flashlight(state: String) = fail("A PC has no flashlight.")
    override fun camera(mode: String) = if (WinActions.openUri("microsoft.windows.camera:")) ok("Opening the camera.") else fail("I couldn't find a camera app.")

    override fun openApp(name: String): ActionResult {
        val hit = WinActions.findApp(name) ?: return fail("I couldn't find an app called $name.")
        return if (WinActions.open(hit.second)) ok("Opening ${hit.first}.") else fail("I couldn't open ${hit.first}.")
    }

    override fun setAlarm(hour: Int, minute: Int, label: String): ActionResult {
        app.belle.assistant.service.Reminders.schedule(ctx, label.ifBlank { "Alarm" }, nextOccurrence(hour, minute), alarm = true)
        return ok("Alarm set for ${SimpleDateFormat("h:mm a", Locale.US).format(Date(nextOccurrence(hour, minute)))}. Keep me running (the tray is fine) so it can ring.")
    }
    override fun setTimer(seconds: Int, label: String): ActionResult {
        app.belle.assistant.service.Reminders.schedule(ctx, label.ifBlank { "Timer" } + " is done", System.currentTimeMillis() + seconds * 1000L, alarm = true)
        return ok("Timer set for ${speakDuration(seconds)}.")
    }
    override fun reminder(text: String, atEpochMillis: Long): ActionResult {
        app.belle.assistant.service.Reminders.schedule(ctx, text, atEpochMillis, alarm = false)
        val mins = ((atEpochMillis - System.currentTimeMillis() + 30_000) / 60_000).toInt()
        return ok(if (mins in 1..120) "Okay, I'll remind you in ${speakDuration(mins * 60)}." else "Okay, I'll remind you at ${SimpleDateFormat("h:mm a", Locale.US).format(Date(atEpochMillis))}.")
    }
    private fun nextOccurrence(h: Int, m: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, m); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
    private fun speakDuration(s: Int): String = when {
        s < 60 -> "$s second${if (s == 1) "" else "s"}"
        s % 3600 == 0 -> "${s / 3600} hour${if (s / 3600 == 1) "" else "s"}"
        s < 3600 -> "${s / 60} minute${if (s / 60 == 1) "" else "s"}" + if (s % 60 != 0) " and ${s % 60} seconds" else ""
        else -> "${s / 3600} hours and ${(s % 3600) / 60} minutes"
    }

    /** An .ics file opened with the default calendar app (Outlook, Windows Calendar, Thunderbird...). */
    override fun calendarEvent(title: String, startEpochMillis: Long?): ActionResult {
        val start = startEpochMillis ?: (System.currentTimeMillis() / 3_600_000 + 1) * 3_600_000
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val ics = File(ctx.cacheDir, "event.ics")
        ics.writeText("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//MilkyMoo//Assistant//EN\r\nBEGIN:VEVENT\r\nUID:${System.nanoTime()}@milkymoo\r\n" +
            "DTSTAMP:${f.format(Date())}\r\nDTSTART:${f.format(Date(start))}\r\nDTEND:${f.format(Date(start + 3_600_000))}\r\nSUMMARY:${title.replace(Regex("[\\r\\n]"), " ")}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        return if (WinActions.open(ics)) ok("I opened your calendar with $title filled in. Save it there.") else fail("I couldn't find a calendar app.")
    }

    override fun volume(level: String, percent: Int?, stream: String): ActionResult {
        val done = when (level) {
            "up" -> WinActions.mediaKey("volup", 5); "down" -> WinActions.mediaKey("voldown", 5)
            "mute" -> WinActions.mediaKey("mute", 1); "unmute" -> WinActions.mediaKey("mute", 1)
            else -> false
        }
        return if (done) ok(when (level) { "up" -> "Turned it up."; "down" -> "Turned it down."; else -> "Done." })
        else fail(if (percent != null) "I can only turn the PC volume up, down or mute it, not set an exact level yet." else "I couldn't change the volume.")
    }
    override fun openSettings(panel: String): ActionResult {
        val uri = when (panel) {
            "wifi", "network" -> "ms-settings:network-wifi"; "bluetooth" -> "ms-settings:bluetooth"; "display", "brightness" -> "ms-settings:display"
            "sound", "volume" -> "ms-settings:sound"; "battery" -> "ms-settings:batterysaver"; "apps" -> "ms-settings:appsfeatures"
            "accessibility" -> "ms-settings:easeofaccess"; "location" -> "ms-settings:privacy-location"; else -> "ms-settings:"
        }
        return if (WinActions.openUri(uri)) ok("Opening settings.") else fail("I couldn't open the settings here.")
    }
    override fun media(command: String): ActionResult {
        val k = when (command) { "play", "pause", "toggle", "resume" -> "playpause"; "next", "skip" -> "next"; "previous", "back" -> "prev"; "stop" -> "stop"; else -> "playpause" }
        return if (WinActions.mediaKey(k, 1)) ok("Done.") else fail("I couldn't control the media player here.")
    }
    override fun navigate(destination: String, mode: String): ActionResult {
        val q = java.net.URLEncoder.encode(destination, "UTF-8")
        return if (WinActions.openUri("bingmaps:?q=$q") || WinActions.browse("https://www.openstreetmap.org/search?query=$q")) ok("Showing $destination on the map.")
        else fail("I couldn't open a map.")
    }

    private val notesFile get() = File(ctx.filesDir, "notes.txt")
    override fun saveNote(text: String): ActionResult {
        notesFile.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\t${text.replace('\n', ' ')}\n")
        return ok("Saved your note.")
    }
    override fun readNotes(): List<String> = if (!notesFile.exists()) emptyList() else notesFile.readLines().filter { it.isNotBlank() }.map { it.substringAfter('\t') }

    override fun deviceInfo(kind: String): ActionResult {
        val now = Date()
        val time = SimpleDateFormat("h:mm a", Locale.US).format(now); val date = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
        val batt = WinActions.battery()
        val battS = batt?.let { (p, ch) -> "Battery is at $p percent" + if (ch) " and charging." else "." } ?: "This PC runs on mains power (no battery)."
        val freeGb = Paths.data.usableSpace / 1e9
        return ok(when (kind) {
            "time" -> "It's $time."; "date" -> "Today is $date."; "battery" -> battS
            "storage" -> "You have %.1f gigabytes free on the drive I use.".format(Locale.US, freeGb)
            "network" -> if (WinActions.online()) "You're connected." else "You're offline."
            else -> "It's $time on $date. $battS"
        })
    }
    override fun screen(op: String, target: String) = fail("Acting on other windows isn't available on the PC version yet.")
    override fun webSearch(query: String): ActionResult =
        if (WinActions.browse("https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, "UTF-8"))) ok("Searching for $query.") else fail("I couldn't find a browser.")
}

/** Desktop twin: there is no accessibility service on the desktop (screen reading of other apps isn't ported). */
class BelleAccessibilityService {
    fun readScreen(): String? = null
    companion object { @JvmField val instance: BelleAccessibilityService? = null }
}
