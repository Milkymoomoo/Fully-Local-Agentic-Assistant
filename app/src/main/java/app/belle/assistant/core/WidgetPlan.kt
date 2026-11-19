package app.belle.assistant.core

/**
 * What the home-screen widget shows, decided in one pure place (unit-tested): ALWAYS the active assistant's own
 * avatar, in 3D only when that assistant has a 3D model and the widget's switch is on 3D, else its 2D picture.
 * Anything cached for the widget (idle loops, live views, stills) belongs to one profile + mode and is reused only
 * for that same profile + mode, so another assistant's avatar can never stay on screen after a switch.
 */
object WidgetPlan {
    data class Plan(val profileId: String, val mode: String, val toggle3dEnabled: Boolean)

    /** [chosen] = the widget's own 2D/3D switch ("2d"/"3d"); [has3d] = whether the ACTIVE profile has a 3D model. */
    fun decide(activeId: String, chosen: String, has3d: Boolean) = Plan(activeId, if (chosen == "3d" && has3d) "3d" else "2d", has3d)

    /** A cached loop/frame made for ([cachedProfile], [cachedMode]) may stand in only for the same profile and mode. */
    fun canReuse(cachedProfile: String?, cachedMode: String?, plan: Plan) = cachedProfile == plan.profileId && cachedMode == plan.mode
}
