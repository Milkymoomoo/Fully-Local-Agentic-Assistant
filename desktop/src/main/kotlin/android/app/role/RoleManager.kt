package android.app.role

/** No "default assistant" role on a PC. */
class RoleManager { fun isRoleAvailable(r: String) = false; fun isRoleHeld(r: String) = false
    companion object { const val ROLE_ASSISTANT = "android.app.role.ASSISTANT" } }
