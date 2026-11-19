package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRouterTest {
    private val belle = ProfileWake("default", "Belle", listOf("hey belle", "okay belle", "hey assistant"))
    private val dixie = ProfileWake("d1", "Dixie", listOf("hey dixie", "hey assistant"))
    private val jarvis = ProfileWake("j1", "Jarvis", listOf("yo jarvis", "hey assistant"))
    private val all = listOf(belle, dixie, jarvis)

    @Test fun singleModeOnlyActiveListensAndAnswers() {
        assertEquals(listOf("hey belle", "okay belle", "hey assistant"), ProfileRouter.listeningPhrases(all, "default", multi = false))
        assertEquals("default", ProfileRouter.resolve("HEY BELLE", all, "default", multi = false))
        assertNull(ProfileRouter.resolve("HEY DIXIE", all, "default", multi = false))     // other profile ignored
        assertEquals("d1", ProfileRouter.resolve("hey dixie", all, "d1", multi = false))
    }

    @Test fun multiModeEachAnswersItsOwn() {
        val listen = ProfileRouter.listeningPhrases(all, "default", multi = true)
        assertEquals(listOf("hey belle", "okay belle", "hey assistant", "hey dixie", "yo jarvis"), listen)  // shared phrase listed once
        assertEquals("d1", ProfileRouter.resolve("HEY DIXIE", all, "default", multi = true))
        assertEquals("j1", ProfileRouter.resolve("YO JARVIS", all, "default", multi = true))
        assertEquals("default", ProfileRouter.resolve("OK BELLE", all, "d1", multi = true))  // "OK" variant of "okay belle"
    }

    @Test fun overlappingPhraseExactlyOneAnswers() {
        // Active wins when it owns the phrase.
        assertEquals("d1", ProfileRouter.resolve("HEY ASSISTANT", all, "d1", multi = true))
        // Active doesn't own it → user priority order decides.
        val noActive = listOf(dixie, jarvis, ProfileWake("x", "X", listOf("hey x")))
        assertEquals("j1", ProfileRouter.resolve("hey assistant", noActive, "x", multi = true, priority = listOf("j1", "d1")))
        assertEquals("d1", ProfileRouter.resolve("hey assistant", noActive, "x", multi = true, priority = listOf("d1", "j1")))
        // No priority set → list order.
        assertEquals("d1", ProfileRouter.resolve("hey assistant", noActive, "x", multi = true))
    }

    @Test fun unknownPhraseIsIgnored() {
        assertNull(ProfileRouter.resolve("HEY SIRI", all, "default", multi = true))
    }
}
