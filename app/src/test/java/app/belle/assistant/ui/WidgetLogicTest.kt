package app.belle.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetLogicTest {
    @Test fun toggleOnlyGoesTo3dWithAMesh() {
        assertEquals("3d", AssistantWidget.toggleTarget("2d", has3d = true))
        assertNull(AssistantWidget.toggleTarget("2d", has3d = false))      // stays on 2D, hint shown
        assertEquals("2d", AssistantWidget.toggleTarget("3d", has3d = true))
        assertEquals("2d", AssistantWidget.toggleTarget("3d", has3d = false))
    }
    @Test fun wakeWordRotatesEveryTenSeconds() {
        val w = listOf("hey bob", "okay bob", "hey bob from accounting")
        assertEquals("hey bob", AssistantWidget.wakeHint(w, "Bob", 0))
        assertEquals("hey bob", AssistantWidget.wakeHint(w, "Bob", 9_999))
        assertEquals("okay bob", AssistantWidget.wakeHint(w, "Bob", 10_000))
        assertEquals("hey bob from accounting", AssistantWidget.wakeHint(w, "Bob", 25_000))
        assertEquals("hey bob", AssistantWidget.wakeHint(w, "Bob", 30_000))
        assertEquals("Bob", AssistantWidget.wakeHint(emptyList(), "Bob", 5))
    }
    @Test fun effectiveModeNeedsMesh() {
        assertEquals("2d", AssistantWidget.effectiveMode("3d", false))
        assertEquals("3d", AssistantWidget.effectiveMode("3d", true))
    }
}

class WidgetMotionTest {
    @Test fun movingPictureOffMeansNothingMoves() {
        assertEquals(AssistantWidget.Companion.Motion(idleLoop = false, liveFrames = false), AssistantWidget.motion(animate = false, inConversation = false))
        assertEquals(AssistantWidget.Companion.Motion(idleLoop = false, liveFrames = false), AssistantWidget.motion(animate = false, inConversation = true))
    }
    @Test fun movingPictureOnLoopsWhenIdleAndGoesLiveWhileTalking() {
        assertEquals(AssistantWidget.Companion.Motion(idleLoop = true, liveFrames = false), AssistantWidget.motion(animate = true, inConversation = false))
        assertEquals(AssistantWidget.Companion.Motion(idleLoop = false, liveFrames = true), AssistantWidget.motion(animate = true, inConversation = true))
    }
}
