package android.media

/** Desktop stand-in: there are no phone ringtones on a PC (custom sounds are picked as files instead). */
object RingtoneManager {
    class Ringtone { fun getTitle(ctx: android.content.Context): String = "" }
    @JvmStatic fun getRingtone(ctx: android.content.Context, uri: android.net.Uri?): Ringtone? = null
    const val ACTION_RINGTONE_PICKER = "android.intent.action.RINGTONE_PICKER"; const val TYPE_ALL = 7; const val TYPE_NOTIFICATION = 2; const val TYPE_ALARM = 4
    const val EXTRA_RINGTONE_TYPE = "android.intent.extra.ringtone.TYPE"; const val EXTRA_RINGTONE_TITLE = "android.intent.extra.ringtone.TITLE"
    const val EXTRA_RINGTONE_SHOW_SILENT = "android.intent.extra.ringtone.SHOW_SILENT"; const val EXTRA_RINGTONE_SHOW_DEFAULT = "android.intent.extra.ringtone.SHOW_DEFAULT"
    const val EXTRA_RINGTONE_PICKED_URI = "android.intent.extra.ringtone.PICKED_URI"; const val EXTRA_RINGTONE_EXISTING_URI = "android.intent.extra.ringtone.EXISTING_URI"
}
