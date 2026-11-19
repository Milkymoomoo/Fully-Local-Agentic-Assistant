package app.belle.assistant.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class ActionResult(val ok: Boolean, val speech: String)

/** Everything the assistant can do on the phone. Android implementation: actions/AndroidPhoneActions. */
interface PhoneActions {
    fun call(contact: String): ActionResult
    fun sms(contact: String, message: String): ActionResult
    fun openApp(name: String): ActionResult
    fun setAlarm(hour: Int, minute: Int, label: String): ActionResult
    fun setTimer(seconds: Int, label: String): ActionResult
    fun flashlight(state: String): ActionResult
    fun volume(level: String, percent: Int?, stream: String): ActionResult
    fun openSettings(panel: String): ActionResult
    fun media(command: String): ActionResult
    fun navigate(destination: String, mode: String): ActionResult
    fun reminder(text: String, atEpochMillis: Long): ActionResult
    fun calendarEvent(title: String, startEpochMillis: Long?): ActionResult
    fun saveNote(text: String): ActionResult
    fun readNotes(): List<String>
    fun deviceInfo(kind: String): ActionResult
    fun screen(op: String, target: String): ActionResult
    fun webSearch(query: String): ActionResult
    fun camera(mode: String): ActionResult
}

/** Online knowledge (web search + news). [enabled] reflects the user's Internet switch. */
interface Knowledge {
    val enabled: Boolean
    fun answer(query: String): WebAnswer
    fun news(topic: String): WebAnswer
}

/**
 * What to say, and optionally an action waiting for a spoken yes/no.
 * [speech] may contain [n] citation markers (shown in the transcript, removed when spoken).
 */
data class Reply(
    val speech: String, val pending: Action? = null, val action: Action? = null, val needs: String? = null,
    val sources: List<Source> = emptyList(), val endSession: Boolean = false,
    /** Small remark for the transcript only (e.g. a search fallback). */
    val note: String? = null,
    /** Already spoken sentence by sentence while it was generated (long-form); the caller only waits for the voice. */
    val streamed: Boolean = false,
)

class ActionRouter(
    private val phone: PhoneActions,
    private val confirmMessages: () -> Boolean = { true },
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val knowledge: Knowledge? = null,
) {
    fun execute(action: Action, confirmed: Boolean = false): Reply {
        val r: ActionResult = when (action.tool) {
            "call" -> {
                val c = action.arg("contact")
                if (c.isEmpty()) return Reply("Who should I call?", action = action, needs = "contact")
                phone.call(c)
            }
            "sms" -> {
                val c = action.arg("contact"); val m = action.arg("message")
                if (c.isEmpty()) return Reply("Who should I text?", action = action, needs = "contact")
                if (m.isEmpty()) return Reply("What should the message say?", action = action, needs = "message")
                if (confirmMessages() && !confirmed) return Reply("Send \"$m\" to $c?", pending = action, action = action)
                phone.sms(c, m)
            }
            "open_app" -> phone.openApp(action.arg("app"))
            "set_alarm" -> {
                val h = action.intArg("hour"); val m = action.intArg("minute") ?: 0
                if (h == null || h !in 0..23 || m !in 0..59) return Reply("What time should the alarm be?", action = action, needs = "time")
                phone.setAlarm(h, m, action.arg("label"))
            }
            "set_timer" -> {
                val s = action.intArg("seconds")
                if (s == null || s <= 0) return Reply("How long should the timer be?", action = action, needs = "duration")
                phone.setTimer(s, action.arg("label"))
            }
            "flashlight" -> phone.flashlight(action.arg("state").ifEmpty { "toggle" })
            "volume" -> phone.volume(action.arg("level").ifEmpty { "up" }, action.intArg("percent"), action.arg("stream").ifEmpty { "media" })
            "open_settings" -> phone.openSettings(action.arg("panel").ifEmpty { "main" })
            "media" -> phone.media(action.arg("command").ifEmpty { "toggle" })
            "navigate" -> {
                val d = action.arg("destination")
                if (d.isEmpty()) return Reply("Where to?", action = action, needs = "destination")
                phone.navigate(d, action.arg("mode").ifEmpty { "drive" })
            }
            "reminder" -> {
                val text = action.arg("text").ifEmpty { "your reminder" }
                val at = reminderTime(action) ?: return Reply("When should I remind you?", action = action, needs = "when")
                phone.reminder(text, at.toInstant().toEpochMilli())
            }
            "calendar_event" -> {
                val title = action.arg("title").ifEmpty { "Event" }
                phone.calendarEvent(title, eventTime(action)?.toInstant()?.toEpochMilli())
            }
            "note" -> {
                val t = action.arg("text")
                if (t.isEmpty()) return Reply("What should the note say?", action = action, needs = "text")
                phone.saveNote(t)
            }
            "read_notes" -> {
                val notes = phone.readNotes()
                ActionResult(true, when {
                    notes.isEmpty() -> "You don't have any notes yet."
                    notes.size == 1 -> "You have one note: ${notes[0]}"
                    else -> "Your latest notes: " + notes.takeLast(5).reversed().joinToString(". ")
                })
            }
            "device_info" -> phone.deviceInfo(action.arg("kind").ifEmpty { "all" })
            "screen" -> {
                val op = action.arg("op")
                if (op.isEmpty()) return Reply("What should I do on the screen?", action = action, needs = "op")
                phone.screen(op, action.arg("target"))
            }
            "web_search" -> phone.webSearch(action.arg("query"))
            "web_answer" -> {
                val q = action.arg("query")
                if (q.isEmpty()) return Reply("What should I look up?", action = action, needs = "query")
                val k = knowledge
                if (k == null || !k.enabled) {
                    return Reply("My internet access is off, so I can't look that up. You can turn it on in Settings, under Internet.", action = action)
                }
                val a = try { k.answer(q) } catch (e: NetDisabledException) { WebAnswer("My internet access is off.", emptyList()) }
                    catch (e: Exception) { WebAnswer("I couldn't reach the internet just now.", emptyList()) }
                return Reply(a.text, action = action, sources = a.sources, note = a.note)
            }
            "news" -> {
                val k = knowledge
                if (k == null || !k.enabled) return Reply("My internet access is off. Turn it on in Settings to hear the news.", action = action)
                val a = try { k.news(action.arg("topic")) } catch (e: Exception) { WebAnswer("I couldn't load the news just now.", emptyList()) }
                return Reply(a.text, action = action, sources = a.sources)
            }
            "camera" -> phone.camera(action.arg("mode").ifEmpty { "photo" })
            "reply" -> ActionResult(true, action.arg("text").ifEmpty { "Sorry, I don't know." })
            "cancel" -> ActionResult(true, "Okay.")
            else -> ActionResult(false, "I can't do that yet.")
        }
        return Reply(r.speech, action = action)
    }

    fun reminderTime(action: Action): ZonedDateTime? {
        val now = clock()
        val minutes = action.intArg("minutes") ?: 0
        val h = action.intArg("hour")
        if (minutes > 0) return now.plusMinutes(minutes.toLong())
        if (h != null && h in 0..23) {
            val m = (action.intArg("minute") ?: 0).coerceIn(0, 59)
            var t = now.withHour(h).withMinute(m).withSecond(0).withNano(0)
            if (!t.isAfter(now)) t = t.plusDays(1)
            return t
        }
        return null
    }

    fun eventTime(action: Action): ZonedDateTime? {
        val now = clock()
        val h = action.intArg("hour") ?: return null
        if (h !in 0..23) return null
        val m = (action.intArg("minute") ?: 0).coerceIn(0, 59)
        val day = action.arg("day").lowercase()
        var date = now.toLocalDate()
        when {
            day == "tomorrow" -> date = date.plusDays(1)
            day.isEmpty() || day == "today" || day == "tonight" -> {}
            else -> DayOfWeek.values().firstOrNull { day.startsWith(it.name.lowercase().take(3)) }?.let {
                date = date.with(TemporalAdjusters.next(it))
            }
        }
        return ZonedDateTime.of(LocalDateTime.of(date, java.time.LocalTime.of(h, m)), now.zone ?: ZoneId.systemDefault())
    }
}
