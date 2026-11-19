package app.belle.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitForSpeechTest {
    @Test fun splitsLongSentencesKeepsShortTogether() {
        val t = "Hi there. The weather today is sunny with a light breeze from the west. It should stay dry until the evening, when clouds roll in."
        val p = splitForSpeech(t)
        assertEquals(2, p.size)
        assertEquals(t, p.joinToString(" "))
    }
    @Test fun keepsDecimalsAndSingleSentence() {
        assertEquals(listOf("It is 3.5 degrees."), splitForSpeech("It is 3.5 degrees."))
        assertEquals(listOf("ok"), splitForSpeech("ok"))
    }
}
