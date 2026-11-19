package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FakePhone : PhoneActions {
    val log = mutableListOf<String>()
    val notes = mutableListOf<String>()
    private fun ok(s: String, speech: String = "done") = ActionResult(true, speech).also { log += s }
    override fun call(contact: String) = ok("call:$contact")
    override fun sms(contact: String, message: String) = ok("sms:$contact:$message")
    override fun openApp(name: String) = ok("open:$name")
    override fun setAlarm(hour: Int, minute: Int, label: String) = ok("alarm:$hour:$minute")
    override fun setTimer(seconds: Int, label: String) = ok("timer:$seconds")
    override fun flashlight(state: String) = ok("flash:$state")
    override fun volume(level: String, percent: Int?, stream: String) = ok("vol:$level:$percent:$stream")
    override fun openSettings(panel: String) = ok("settings:$panel")
    override fun media(command: String) = ok("media:$command")
    override fun navigate(destination: String, mode: String) = ok("nav:$destination:$mode")
    override fun reminder(text: String, atEpochMillis: Long) = ok("remind:$text:$atEpochMillis")
    override fun calendarEvent(title: String, startEpochMillis: Long?) = ok("cal:$title:$startEpochMillis")
    override fun saveNote(text: String) = ok("note:$text").also { notes += text }
    override fun readNotes() = notes
    override fun deviceInfo(kind: String) = ok("info:$kind")
    override fun screen(op: String, target: String) = ok("screen:$op:$target")
    override fun webSearch(query: String) = ok("search:$query")
    override fun camera(mode: String) = ok("camera:$mode")
}

class FakeLlm(private val answer: String) : LlmBackend {
    var calls = 0
    override val isReady = true
    override fun complete(system: String, user: String, grammar: String, maxTokens: Int): String { calls++; return answer }
}

class RouterAndBrainTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, zone)  // Monday 10:00

    private fun brain(phone: FakePhone, llm: LlmBackend? = null) =
        Brain({ RuleParser(listOf("Belle")) }, { llm }, ActionRouter(phone, { true }, { now }), { "Belle" })

    @Test fun ruleCommandRunsWithoutLlm() {
        val phone = FakePhone(); val llm = FakeLlm("{}")
        val b = brain(phone, llm)
        b.handle("Hey Belle, set a timer for 5 minutes")
        assertEquals(listOf("timer:300"), phone.log)
        assertEquals(0, llm.calls)
        assertEquals("rules", b.lastSource)
    }

    @Test fun llmFallbackIsValidated() {
        val phone = FakePhone()
        val b = brain(phone, FakeLlm("{\"tool\":\"flashlight\",\"args\":{\"state\":\"on\"}}"))
        b.handle("it's really dark in here")
        assertEquals(listOf("flash:on"), phone.log)
        assertEquals("llm", b.lastSource)
    }

    @Test fun invalidLlmOutputIsRejected() {
        val phone = FakePhone()
        val b = brain(phone, FakeLlm("{\"tool\":\"format_disk\",\"args\":{}}"))
        val r = b.handle("do something weird")
        assertTrue(phone.log.isEmpty())
        assertTrue(r.speech.startsWith("Sorry"))
    }

    @Test fun smsNeedsSpokenConfirmation() {
        val phone = FakePhone()
        val b = brain(phone)
        val r = b.handle("text mom saying on my way")
        assertTrue(r.speech.contains("Send"))
        assertTrue(phone.log.isEmpty())
        b.handle("yes")
        assertEquals(listOf("sms:mom:on my way"), phone.log)
    }

    @Test fun smsCanBeDeclined() {
        val phone = FakePhone()
        val b = brain(phone)
        b.handle("text mom saying on my way")
        val r = b.handle("no")
        assertTrue(phone.log.isEmpty())
        assertEquals("Okay, I won't.", r.speech)
    }

    @Test fun followUpFillsMissingSlot() {
        val phone = FakePhone()
        val b = brain(phone, FakeLlm("{\"tool\":\"call\",\"args\":{\"contact\":\"\"}}"))
        val r = b.handle("make a phone call")
        assertEquals("Who should I call?", r.speech)
        b.handle("Grandma")
        assertEquals(listOf("call:Grandma"), phone.log)
    }

    @Test fun noLlmGivesHelpfulMessage() {
        val r = brain(FakePhone()).handle("what's the meaning of life")
        assertTrue(r.speech.contains("Download a language model"))
    }

    @Test fun reminderTimes() {
        val router = ActionRouter(FakePhone(), { true }, { now })
        val inTen = router.reminderTime(Action("reminder", mapOf("minutes" to "10")))!!
        assertEquals(now.plusMinutes(10), inTen)
        val at9 = router.reminderTime(Action("reminder", mapOf("minutes" to "0", "hour" to "9", "minute" to "0")))!!
        assertEquals(17, at9.dayOfMonth)  // 9:00 already passed today -> tomorrow
        val at18 = router.reminderTime(Action("reminder", mapOf("hour" to "18", "minute" to "30")))!!
        assertEquals(16, at18.dayOfMonth); assertEquals(18, at18.hour)
        assertNull(router.reminderTime(Action("reminder", mapOf("text" to "x"))))
    }

    @Test fun calendarDays() {
        val router = ActionRouter(FakePhone(), { true }, { now })
        val fri = router.eventTime(Action("calendar_event", mapOf("day" to "friday", "hour" to "14", "minute" to "0")))!!
        assertEquals(20, fri.dayOfMonth)
        val tom = router.eventTime(Action("calendar_event", mapOf("day" to "tomorrow", "hour" to "9")))!!
        assertEquals(17, tom.dayOfMonth)
    }

    @Test fun notesRoundTrip() {
        val phone = FakePhone(); val b = brain(phone)
        b.handle("take a note that the gate code is 4321")
        val r = b.handle("read my notes")
        assertTrue(r.speech.contains("4321"))
    }
}
