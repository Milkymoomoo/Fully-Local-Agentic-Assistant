package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression (real phone, 0.1.0): Milly active with only a 2D picture, but the widget showed Bob's 3D model. */
class WidgetPlanTest {
    private val has3d = mapOf("bob" to true, "milly" to false)

    @Test fun showsTheActiveAssistantIn2DWhenItHasNo3DModel() {
        // the widget's switch was left on 3D while Bob (with a 3D model) was active
        val bobPlan = WidgetPlan.decide("bob", "3d", has3d.getValue("bob"))
        assertEquals(WidgetPlan.Plan("bob", "3d", true), bobPlan)
        // Milly becomes active: the widget shows MILLY, in 2D, and its 3D option is disabled
        val milly = WidgetPlan.decide("milly", "3d", has3d.getValue("milly"))
        assertEquals("milly", milly.profileId); assertEquals("2d", milly.mode); assertFalse(milly.toggle3dEnabled)
        // nothing cached for Bob may stand in for Milly (the old fallback kept the previous loop on screen)
        assertFalse(WidgetPlan.canReuse("bob", "3d", milly))
        assertFalse(WidgetPlan.canReuse("bob", "2d", milly))
        assertFalse(WidgetPlan.canReuse("milly", "3d", milly))
        assertTrue(WidgetPlan.canReuse("milly", "2d", milly))
    }

    @Test fun switchingBackRestores3DOnlyForTheAssistantThatHasIt() {
        assertEquals("3d", WidgetPlan.decide("bob", "3d", true).mode)
        assertEquals("2d", WidgetPlan.decide("bob", "2d", true).mode)
        assertEquals("2d", WidgetPlan.decide("milly", "3d", false).mode)
        assertFalse(WidgetPlan.canReuse(null, null, WidgetPlan.decide("bob", "3d", true)))
    }
}
