package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTest {
    @Test fun multiSpeakerVoicesAreKnown() {
        val byId = ModelCatalog.VOICES.associateBy { it.id }
        assertEquals(11, ModelCatalog.KOKORO.speakers)
        assertEquals(904, byId["en_US-libritts_r-medium"]!!.speakers)
        assertEquals(904, byId["en_US-libritts-high-int8"]!!.speakers)
        assertEquals(904, byId["en_US-libritts-high-fp32"]!!.speakers)
        assertEquals(1, byId["en_US-ryan-medium"]!!.speakers)
        // every multi-speaker voice is one the picker can show
        ModelCatalog.VOICES.filter { it.speakers > 1 }.forEach { v -> assert(v.engine == "kokoro" || "libritts" in v.id) { v.id } }
    }
    @Test fun kokoroSpeakerNames() {
        val k = ModelCatalog.KOKORO
        assertEquals("Mixed · US female", k.speakerLabel(0))
        assertEquals("Bella · US female", k.speakerLabel(1))
        assertEquals("Adam · US male", k.speakerLabel(5))
        assertEquals("Emma · British female", k.speakerLabel(7))
        assertEquals("Lewis · British male", k.speakerLabel(10))
        assertEquals("Speaker 11", k.speakerLabel(11))
        assertEquals("Speaker 12", speakerName("piper", 12))
    }
}
