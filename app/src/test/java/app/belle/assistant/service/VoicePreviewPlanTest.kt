package app.belle.assistant.service

import app.belle.assistant.speech.VoicePreview
import app.belle.assistant.speech.VoicePreview.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePreviewPlanTest {
    private val web = "https://rhasspy.github.io/piper-samples/samples/en/en_US/amy/medium/speaker_0.mp3"

    @Test fun internetOffNeverDownloads() {
        assertEquals(Plan.NEEDS_INTERNET, VoicePreview.plan(installed = false, sampleUrl = web, cached = false, internetOn = false))
    }
    @Test fun internetOnDownloadsOnce() {
        assertEquals(Plan.DOWNLOAD, VoicePreview.plan(false, web, cached = false, internetOn = true))
        assertEquals(Plan.CACHED, VoicePreview.plan(false, web, cached = true, internetOn = true))
    }
    @Test fun offlinePathsWorkWithInternetOff() {
        assertEquals("installed voice speaks live", Plan.LIVE, VoicePreview.plan(true, web, false, internetOn = false))
        assertEquals("kept sample still plays", Plan.CACHED, VoicePreview.plan(false, web, cached = true, internetOn = false))
        assertEquals("bundled Kokoro clip", Plan.BUNDLED, VoicePreview.plan(false, "asset:voice_samples/k.ogg", false, internetOn = false))
        assertEquals(Plan.NO_SAMPLE, VoicePreview.plan(false, "", false, internetOn = false))
    }
}
