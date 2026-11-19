package app.belle.assistant.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import app.belle.assistant.core.ActionResult
import app.belle.assistant.core.PhoneActions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Real phone actions, using standard Android intents/APIs only (no Google services). */
class AndroidPhoneActions(private val ctx: Context) : PhoneActions {

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun start(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (backgroundBlocked() && i.resolveActivity(ctx.packageManager) != null) { deferToNotification(i); true }
        else { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(4242); ctx.startActivity(i); true }
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    /**
     * Android 10+ silently drops activity starts from an app that isn't on screen, unless it may draw over other
     * apps. Then the action goes into a heads-up notification the user taps, and the reply says so.
     */
    private fun backgroundBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(ctx)) return false
        val st = android.app.ActivityManager.RunningAppProcessInfo(); android.app.ActivityManager.getMyMemoryState(st)
        return st.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
    private var deferred = false
    private fun deferToNotification(i: Intent) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java); val ch = "belle_open"
        if (nm.getNotificationChannel(ch) == null) nm.createNotificationChannel(android.app.NotificationChannel(ch, "Apps to open", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, (System.currentTimeMillis() % 100000).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = i.resolveActivity(ctx.packageManager)?.let { cn -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(cn.packageName, 0)).toString() }.getOrNull() } ?: "it"
        val n = android.app.Notification.Builder(ctx, ch).setSmallIcon(app.belle.assistant.R.drawable.ic_mic).setContentTitle("Tap to open $label")
            .setContentText("Allow \"Display over other apps\" and I'll open apps directly.").setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(120_000)
            .setCategory(android.app.Notification.CATEGORY_RECOMMENDATION).build()
        nm.notify(4242, n); deferred = true
    }

    private fun ok(s: String) = ActionResult(true, if (deferred) { deferred = false; "$s Tap the notification — I need \"Display over other apps\" to open it myself." } else s)
    private fun fail(s: String) = ActionResult(false, s)

    // ---- contacts -------------------------------------------------------
    data class Contact(val name: String, val number: String)

    private val relations = mapOf("mom" to listOf("mom", "mother", "mama", "mum", "momma"), "dad" to listOf("dad", "father", "papa", "daddy"))

    fun findContact(query: String): Contact? {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && query.count { it.isLetter() } == 0) return Contact(digits, digits)
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val q = query.lowercase().removePrefix("my ").trim()
        val candidates = relations.entries.firstOrNull { q in it.value }?.value ?: listOf(q)
        var best: Contact? = null
        var bestScore = 0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val n = name.lowercase()
                val score = candidates.maxOf { cand ->
                    when {
                        n == cand -> 100
                        n.split(" ").firstOrNull() == cand -> 80
                        n.startsWith(cand) -> 60
                        n.contains(cand) -> 40
                        else -> 0
                    }
                }
                if (score > bestScore) { bestScore = score; best = Contact(name, num) }
            }
        }
        return best
    }

    override fun call(contact: String): ActionResult {
        val c = findContact(contact)
            ?: return if (!has(Manifest.permission.READ_CONTACTS)) fail("I need contacts permission to find $contact.") else fail("I couldn't find $contact in your contacts.")
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        return if (has(Manifest.permission.CALL_PHONE) && start(Intent(Intent.ACTION_CALL, uri))) ok("Calling ${c.name}.")
        else if (start(Intent(Intent.ACTION_DIAL, uri))) ok("Dialing ${c.name}. Tap call to connect.")
        else fail("I couldn't open the phone app.")
    }

    @SuppressLint("MissingPermission")
    override fun sms(contact: String, message: String): ActionResult {
        val c = findContact(contact) ?: return fail("I couldn't find $contact in your contacts.")
        if (has(Manifest.permission.SEND_SMS)) {
            return try {
                val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                sm.sendMultipartTextMessage(c.number, null, sm.divideMessage(message), null, null)
                ok("Sent to ${c.name}.")
            } catch (e: Exception) { fail("The message failed to send.") }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", message)
        return if (start(i)) ok("I opened the message to ${c.name}. Tap send.") else fail("I couldn't open messages.")
    }

    // ---- apps -----------------------------------------------------------
    fun launchableApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    override fun openApp(name: String): ActionResult {
        val q = name.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        if (q.isEmpty()) return fail("Which app?")
        val apps = launchableApps()
        val best = apps.maxByOrNull { (label, pkg) -> AppMatcher.score(q, label, pkg) }
        if (best == null || AppMatcher.score(q, best.first, best.second) < 50) return fail("I couldn't find an app called $name.")
        val launch = ctx.packageManager.getLaunchIntentForPackage(best.second) ?: return fail("I couldn't open ${best.first}.")
        return if (start(launch)) ok("Opening ${best.first}.") else fail("I couldn't open ${best.first}.")
    }

    // ---- clock ----------------------------------------------------------
    override fun setAlarm(hour: Int, minute: Int, label: String): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val spoken = speakTime(hour, minute)
        return if (start(i)) ok("Alarm set for $spoken.") else {
            // No clock app: fall back to our own alarm notification.
            scheduleOwn("Alarm", nextOccurrence(hour, minute), alarm = true)
            ok("Alarm set for $spoken.")
        }
    }

    override fun setTimer(seconds: Int, label: String): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (!start(i)) scheduleOwn(label.ifBlank { "Timer" } + " is done", System.currentTimeMillis() + seconds * 1000L, alarm = true)
        return ok("Timer set for ${speakDuration(seconds)}.")
    }

    private fun nextOccurrence(h: Int, m: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, m); cal.set(java.util.Calendar.SECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** Saved, so it still fires after a reboot / update / force stop (see service/Reminders). */
    private fun scheduleOwn(text: String, at: Long, alarm: Boolean) { app.belle.assistant.service.Reminders.schedule(ctx, text, at, alarm) }

    override fun reminder(text: String, atEpochMillis: Long): ActionResult {
        scheduleOwn(text, atEpochMillis, alarm = false)
        val fmt = SimpleDateFormat("h:mm a", Locale.US).format(Date(atEpochMillis))
        val mins = ((atEpochMillis - System.currentTimeMillis() + 30_000) / 60_000).toInt()
        return ok(if (mins in 1..120) "Okay, I'll remind you in ${speakDuration(mins * 60)}." else "Okay, I'll remind you at $fmt.")
    }

    override fun calendarEvent(title: String, startEpochMillis: Long?): ActionResult {
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (startEpochMillis != null) {
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochMillis)
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startEpochMillis + 3_600_000)
        }
        return if (start(i)) ok("I opened your calendar with $title filled in. Tap save.") else fail("I couldn't find a calendar app.")
    }

    // ---- hardware -------------------------------------------------------
    private var torchOn = false

    override fun flashlight(state: String): ActionResult {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return fail("This phone doesn't have a flashlight.")
        val on = when (state) { "on" -> true; "off" -> false; else -> !torchOn }
        return try {
            cm.setTorchMode(id, on); torchOn = on
            ok(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) { fail("I couldn't use the flashlight right now.") }
    }

    override fun volume(level: String, percent: Int?, stream: String): ActionResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        return try {
            when (level) {
                "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
                "min" -> am.setStreamVolume(s, 0, AudioManager.FLAG_SHOW_UI)
                "set" -> am.setStreamVolume(s, ((percent ?: 50).coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            }
            val pct = am.getStreamVolume(s) * 100 / maxOf(1, max)
            ok(if (level == "mute") "Muted." else "Volume is at $pct percent.")
        } catch (e: SecurityException) {
            fail("Do Not Disturb is blocking volume changes. Allow Do Not Disturb access for me in settings.")
        }
    }

    override fun openSettings(panel: String): ActionResult {
        val action = when (panel) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "internet", "mobile_data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.Panel.ACTION_NFC
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "assistant" -> Settings.ACTION_VOICE_INPUT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val label = if (panel == "main") "settings" else panel.replace('_', ' ') + " settings"
        return if (start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))) ok("Opening $label.") else fail("I couldn't open settings.")
    }

    override fun media(command: String): ActionResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok(when (command) { "next" -> "Skipping."; "previous" -> "Going back."; "pause", "stop" -> "Paused."; else -> "Okay." })
    }

    override fun navigate(destination: String, mode: String): ActionResult {
        val q = Uri.encode(destination)
        // geo: works with any maps app (OsmAnd, Organic Maps, Google Maps...). "google.navigation" only if present.
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=" + when (mode) { "walk" -> "w"; "bike" -> "b"; else -> "d" }))
        if (mode != "map" && nav.resolveActivity(ctx.packageManager) != null && start(nav)) return ok("Starting directions to $destination.")
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))) ok("Showing $destination on the map.")
        else fail("I couldn't find a maps app. Organic Maps or OsmAnd work offline.")
    }

    // ---- notes ----------------------------------------------------------
    private val notesFile get() = File(ctx.filesDir, "notes.txt")

    override fun saveNote(text: String): ActionResult {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        notesFile.appendText("$stamp\t${text.replace('\n', ' ')}\n")
        return ok("Saved your note.")
    }

    override fun readNotes(): List<String> =
        if (!notesFile.exists()) emptyList() else notesFile.readLines().filter { it.isNotBlank() }.map { it.substringAfter('\t') }

    // ---- info -----------------------------------------------------------
    override fun deviceInfo(kind: String): ActionResult {
        val now = Date()
        val time = SimpleDateFormat("h:mm a", Locale.US).format(now)
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val batt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1e9
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "You're offline."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "You're on Wi-Fi."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "You're on mobile data."
            else -> "You're connected."
        }
        val battS = "Battery is at $batt percent" + if (charging) " and charging." else "."
        return ok(when (kind) {
            "time" -> "It's $time."
            "date" -> "Today is $date."
            "battery" -> battS
            "storage" -> "You have %.1f gigabytes free.".format(Locale.US, freeGb)
            "network" -> net
            else -> "It's $time on $date. $battS"
        })
    }

    override fun screen(op: String, target: String): ActionResult {
        val svc = BelleAccessibilityService.instance
            ?: return fail("To act on the screen I need Accessibility turned on (it is optional): Settings, Accessibility, Installed apps, then my name, then On. The Turn on button in my Permissions settings takes you there.")
        return svc.perform(op, target)
    }

    override fun webSearch(query: String): ActionResult {
        // Hands the query to your browser; this app itself never goes online for it.
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query)))
        return if (start(i)) ok("Searching for $query.")
        else if (start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))) ok("Searching for $query.")
        else fail("I couldn't find a browser.")
    }

    override fun camera(mode: String): ActionResult {
        val i = when (mode) {
            "video" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            else -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        }
        if (mode == "selfie") {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return if (start(i)) ok("Opening the camera.") else fail("I couldn't open the camera.")
    }

    companion object {
        fun speakTime(h: Int, m: Int): String {
            val h12 = if (h % 12 == 0) 12 else h % 12
            val ap = if (h < 12) "AM" else "PM"
            return if (m == 0) "$h12 $ap" else "$h12:%02d $ap".format(m)
        }
        fun speakDuration(seconds: Int): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts += "$h hour" + if (h > 1) "s" else ""
            if (m > 0) parts += "$m minute" + if (m > 1) "s" else ""
            if (s > 0) parts += "$s second" + if (s > 1) "s" else ""
            return parts.joinToString(" and ").ifEmpty { "0 seconds" }
        }
    }
}

/** Fuzzy app-name matching ("youtube music" vs "YT Music", "maps" vs "Google Maps"). Pure, unit-tested. */
object AppMatcher {
    private val aliases = mapOf(
        "youtube" to listOf("yt"), "messages" to listOf("messaging", "sms", "texts"),
        "phone" to listOf("dialer", "calls"), "camera" to listOf("cam"),
        "settings" to listOf("setting"), "gallery" to listOf("photos", "pictures"),
        "browser" to listOf("chrome", "firefox", "brave", "internet", "samsung internet"),
    )

    fun score(query: String, label: String, pkg: String): Int {
        val q = query.lowercase().trim()
        val l = label.lowercase().trim()
        if (q == l) return 100
        val qWords = q.split(" ").filter { it.isNotEmpty() }
        if (l.split(" ").containsAll(qWords) && qWords.isNotEmpty()) return 85 - (l.length - q.length).coerceAtMost(20)
        if (l.startsWith(q)) return 80
        if (l.contains(q)) return 70
        for ((k, v) in aliases) {
            if ((q == k || q in v) && (l.contains(k) || v.any { l.contains(it) })) return 65
        }
        if (pkg.lowercase().contains(q.replace(" ", ""))) return 55
        val ed = editDistance(q, l)
        if (ed <= maxOf(1, q.length / 4)) return 60 - ed
        return 0
    }

    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
