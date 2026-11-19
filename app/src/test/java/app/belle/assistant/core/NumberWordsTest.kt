package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberWordsTest {
    @Test fun digitize() {
        assertEquals("25 minutes", NumberWords.digitize("twenty five minutes"))
        assertEquals("1 hour", NumberWords.digitize("an hour"))
        assertEquals("150", NumberWords.digitize("a hundred and fifty"))
        assertEquals("call a friend", NumberWords.digitize("call a friend"))
    }
    @Test fun durations() {
        assertEquals(300, NumberWords.parseDurationSeconds("five minutes"))
        assertEquals(5400, NumberWords.parseDurationSeconds("an hour and a half"))
        assertEquals(150, NumberWords.parseDurationSeconds("two and a half minutes"))
        assertEquals(1800, NumberWords.parseDurationSeconds("half an hour"))
        assertEquals(45, NumberWords.parseDurationSeconds("45 seconds"))
        assertEquals(3900, NumberWords.parseDurationSeconds("1 hour and 5 minutes"))
        assertNull(NumberWords.parseDurationSeconds("pizza"))
    }
    @Test fun clock() {
        assertEquals(NumberWords.ClockTime(7, 30), NumberWords.parseClock("7:30 am"))
        assertEquals(NumberWords.ClockTime(19, 30), NumberWords.parseClock("seven thirty pm"))
        assertEquals(NumberWords.ClockTime(0, 0), NumberWords.parseClock("midnight"))
        assertEquals(NumberWords.ClockTime(0, 15), NumberWords.parseClock("12:15 am"))
        assertEquals(NumberWords.ClockTime(17, 45), NumberWords.parseClock("quarter to six"))
        assertEquals(NumberWords.ClockTime(6, 0), NumberWords.parseClock("6", preferAm = true))
        assertEquals(NumberWords.ClockTime(18, 0), NumberWords.parseClock("6"))
        assertEquals(NumberWords.ClockTime(9, 5), NumberWords.parseClock("9 oh 5 in the morning"))
        assertEquals(NumberWords.ClockTime(14, 0), NumberWords.parseClock("2 pm"))
        assertEquals(NumberWords.ClockTime(6, 30), NumberWords.parseClock("630 am"))
    }
}
