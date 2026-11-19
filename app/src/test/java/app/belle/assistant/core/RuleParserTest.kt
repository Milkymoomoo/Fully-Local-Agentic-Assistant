package app.belle.assistant.core

import app.belle.assistant.actions.AppMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleParserTest {
    private val p = RuleParser(listOf("Belle", "Dixie"))

    private fun check(utt: String, tool: String, vararg args: Pair<String, String>) {
        val a = p.parse(utt) ?: throw AssertionError("no parse for: $utt")
        assertEquals("tool for '$utt' (got $a)", tool, a.tool)
        for ((k, v) in args) assertEquals("arg $k for '$utt'", v, a.arg(k))
    }

    @Test fun flashlight() {
        check("Turn on the flashlight", "flashlight", "state" to "on")
        check("hey belle, turn off the flashlight please", "flashlight", "state" to "off")
        check("flashlight", "flashlight", "state" to "toggle")
        check("turn the torch on", "flashlight", "state" to "on")
        check("Dixie flash light off", "flashlight", "state" to "off")
    }

    @Test fun timers() {
        check("set a timer for five minutes", "set_timer", "seconds" to "300")
        check("Timer for 90 seconds.", "set_timer", "seconds" to "90")
        check("start a timer for an hour and a half", "set_timer", "seconds" to "5400")
        check("set a 10 minute timer", "set_timer", "seconds" to "600")
        check("set a timer for 2 minutes called pasta", "set_timer", "seconds" to "120", "label" to "pasta")
        check("can you set a timer for twenty five minutes", "set_timer", "seconds" to "1500")
        check("timer for half an hour", "set_timer", "seconds" to "1800")
        check("set a timer for 1 hour 15 minutes", "set_timer", "seconds" to "4500")
    }

    @Test fun alarms() {
        check("set an alarm for 7:30 am", "set_alarm", "hour" to "7", "minute" to "30")
        check("wake me up at six", "set_alarm", "hour" to "6", "minute" to "0")
        check("set alarm for 6:45 pm", "set_alarm", "hour" to "18", "minute" to "45")
        check("alarm at seven thirty in the morning", "set_alarm", "hour" to "7", "minute" to "30")
        check("set an alarm for noon", "set_alarm", "hour" to "12", "minute" to "0")
        check("set an alarm for quarter past 8", "set_alarm", "hour" to "8", "minute" to "15")
    }

    @Test fun reminders() {
        check("remind me to take the trash out in 20 minutes", "reminder", "text" to "take the trash out", "minutes" to "20")
        check("remind me in an hour to call grandma", "reminder", "text" to "call grandma", "minutes" to "60")
        check("remind me to feed the dog at 6 pm", "reminder", "text" to "feed the dog", "hour" to "18", "minute" to "0")
        check("remind me at 5:30 to leave", "reminder", "text" to "leave", "hour" to "17", "minute" to "30")
    }

    @Test fun calls() {
        check("call mom", "call", "contact" to "mom")
        check("Hey Belle, call John Smith on speaker", "call", "contact" to "john smith")
        check("dial 555 123 4567", "call", "contact" to "555 123 4567")
    }

    @Test fun sms() {
        check("text mom saying I'll be home soon", "sms", "contact" to "mom", "message" to "i'll be home soon")
        check("send a message to Jake that the game starts at 8", "sms", "contact" to "jake", "message" to "the game starts at 8")
        check("text Sarah and tell her I love her", "sms", "contact" to "sarah", "message" to "i love her")
    }

    @Test fun apps() {
        check("open spotify", "open_app", "app" to "spotify")
        check("launch the YouTube app", "open_app", "app" to "youtube")
        check("open google maps", "open_app", "app" to "google maps")
        check("open the camera", "camera", "mode" to "photo")
        check("take a selfie", "camera", "mode" to "selfie")
    }

    @Test fun settings() {
        check("turn on wifi", "open_settings", "panel" to "wifi")
        check("turn bluetooth off", "open_settings", "panel" to "bluetooth")
        check("open wi-fi settings", "open_settings", "panel" to "wifi")
        check("open settings", "open_settings", "panel" to "main")
        check("battery settings", "open_settings", "panel" to "battery")
    }

    @Test fun volume() {
        check("turn the volume up", "volume", "level" to "up")
        check("louder", "volume", "level" to "up")
        check("turn it down", "volume", "level" to "down")
        check("set volume to fifty percent", "volume", "level" to "set", "percent" to "50")
        check("mute", "volume", "level" to "mute")
        check("turn the ringer volume up", "volume", "level" to "up", "stream" to "ring")
    }

    @Test fun media() {
        check("pause the music", "media", "command" to "pause")
        check("play", "media", "command" to "play")
        check("next song", "media", "command" to "next")
        check("skip", "media", "command" to "next")
        check("previous track", "media", "command" to "previous")
    }

    @Test fun info() {
        check("what time is it", "device_info", "kind" to "time")
        check("What's the date today?", "device_info", "kind" to "date")
        check("how much battery do I have", "device_info", "kind" to "battery")
        check("what's my battery level", "device_info", "kind" to "battery")
    }

    @Test fun screen() {
        check("go back", "screen", "op" to "back")
        check("go home", "screen", "op" to "home")
        check("scroll down", "screen", "op" to "scroll_down")
        check("tap the subscribe button", "screen", "op" to "tap", "target" to "subscribe")
        check("click on sign in", "screen", "op" to "tap", "target" to "sign in")
        check("type hello world", "screen", "op" to "type", "target" to "hello world")
        check("read the screen", "screen", "op" to "read_screen")
        check("show notifications", "screen", "op" to "notifications")
        check("lock the phone", "screen", "op" to "lock")
    }

    @Test fun notesNavSearch() {
        check("take a note that the wifi password is moose", "note", "text" to "the wifi password is moose")
        check("read my notes", "read_notes")
        check("navigate to the nearest gas station", "navigate", "destination" to "the nearest gas station", "mode" to "drive")
        check("take me to 123 main street", "navigate", "destination" to "123 main street")
        check("where's the nearest pharmacy", "navigate", "destination" to "pharmacy", "mode" to "map")
        check("search for chocolate chip cookie recipes", "web_answer", "query" to "chocolate chip cookie recipes")
        check("search for cookie recipes in the browser", "web_search", "query" to "cookie recipes")
        check("what's the news", "news", "topic" to "")
        check("latest news about the hurricane", "news", "topic" to "the hurricane")
        check("look up how tall mount everest is", "web_answer", "query" to "how tall mount everest is")
        check("wake me up tomorrow at half past six", "set_alarm", "hour" to "6", "minute" to "30")
    }

    @Test fun cancel() {
        check("never mind", "cancel")
        check("Cancel.", "cancel")
    }

    /** Things the rules must NOT grab; the LLM handles them. */
    @Test fun unknownGoesToLlm() {
        assertNull(p.parse("I'm feeling kind of sad today"))
        assertNull(p.parse("what's the capital of france"))
        assertNull(p.parse("it's really dark in here"))
        assertNull(p.parse("let my wife know I'm running late"))
    }

    @Test fun appMatcher() {
        assertTrue(AppMatcher.score("youtube", "YouTube", "com.google.android.youtube") >= 90)
        assertTrue(AppMatcher.score("maps", "Google Maps", "com.google.android.apps.maps") >= 50)
        assertTrue(AppMatcher.score("spotfy", "Spotify", "com.spotify.music") >= 50)
        assertTrue(AppMatcher.score("messages", "Samsung Messages", "com.samsung.android.messaging") >= 50)
        assertEquals(0, AppMatcher.score("calculator", "Spotify", "com.spotify.music"))
    }
}
