package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoiceTest {
    private val installed = mutableListOf("small.gguf", "big.gguf")
    private val choice = HashMap<String, String>()   // per profile
    private var profile = "a"
    private var globalLast = ""
    private val loads = mutableListOf<String>()
    private var unloads = 0
    private fun gate() = LlmOnDemand({ installed.toList() }, { choice[profile] ?: "" }, { globalLast },
        { f -> loads += f; f in installed }, { unloads++ })

    @Test fun profileChoiceWins() {
        choice["a"] = "big.gguf"; globalLast = "small.gguf"
        assertEquals("big.gguf", ModelChoice.resolve("big.gguf", "small.gguf", installed).file)
    }

    @Test fun serviceRestartKeepsSelection() {
        choice["a"] = "big.gguf"
        val first = gate(); assertEquals("big.gguf", first.ensure().file); assertEquals("big.gguf", first.loaded)
        // service restarted: a fresh gate reading the same persisted prefs loads the same model, no reselecting
        val second = gate(); assertEquals("big.gguf", second.ensure().file); assertEquals("big.gguf", second.loaded)
    }

    @Test fun unloadThenNextRequestReloads() {
        choice["a"] = "big.gguf"
        val g = gate(); g.ensure(); g.unload()
        assertEquals("", g.loaded)
        assertEquals("big.gguf", g.pick().file)              // unloaded is still "selected"
        assertEquals("big.gguf", g.ensure().file); assertEquals("big.gguf", g.loaded)
        assertEquals(listOf("big.gguf", "big.gguf"), loads)
    }

    @Test fun profileSwitchToProfileWithoutChoiceUsesLastChoice() {
        choice["a"] = "big.gguf"; globalLast = "big.gguf"
        val g = gate(); g.ensure()
        profile = "b"                                        // b never picked one
        val p = g.ensure(); assertEquals("big.gguf", p.file); assertTrue(p.note.contains("last choice"))
        assertEquals(1, loads.size)                          // same file: not reloaded
    }

    @Test fun profileSwitchToDifferentModelSwapsIt() {
        choice["a"] = "big.gguf"; choice["b"] = "small.gguf"
        val g = gate(); g.ensure(); profile = "b"; g.ensure()
        assertEquals("small.gguf", g.loaded); assertEquals(1, unloads)
    }

    @Test fun missingFileIsNamedAndFallsBack() {
        choice["a"] = "gone.gguf"
        val p = gate().ensure(); assertEquals("small.gguf", p.file); assertTrue(p.note.contains("gone.gguf"))
        installed.clear()
        val none = gate().ensure(); assertNull(none.file); assertTrue(none.note.contains("gone.gguf"))
        assertTrue(ModelChoice.noModelSpeech(none, null).contains("gone.gguf"))
    }

    @Test fun nothingInstalledIsTheOnlyNoModelCase() {
        installed.clear()
        assertEquals("Sorry, I didn't understand that. Download a thinking model in Settings so I can understand more.",
            ModelChoice.noModelSpeech(gate().ensure(), null))
    }

    @Test fun loadFailureIsReportedWithTheFile() {
        choice["a"] = "big.gguf"
        val g = LlmOnDemand({ installed }, { choice[profile] ?: "" }, { "" }, { false }, {})
        g.ensure(); assertEquals("big.gguf", g.lastFailure)
        assertTrue(ModelChoice.noModelSpeech(g.pick(), g.lastFailure).contains("big.gguf"))
    }
}
