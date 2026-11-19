package android.provider

/** Desktop stand-in: Android's system settings screens. The desktop has no overlay permission to grant. */
object Settings {
    const val ACTION_SETTINGS = "android.settings.SETTINGS"
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val ACTION_MANAGE_OVERLAY_PERMISSION = "android.settings.action.MANAGE_OVERLAY_PERMISSION"
    const val ACTION_REQUEST_SCHEDULE_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
    const val ACTION_VOICE_INPUT_SETTINGS = "android.settings.VOICE_INPUT_SETTINGS"
    const val ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    @JvmStatic fun canDrawOverlays(ctx: android.content.Context) = true
}
