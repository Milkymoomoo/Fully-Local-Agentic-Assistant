package app.belle.assistant.ui

import app.belle.assistant.core.Platform
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.belle.assistant.avatar.Backgrounds
import app.belle.assistant.service.AssistantService
import java.io.File

/** Per-profile background behind the avatar (Home circle, bubble and widget). Opened from the widget's picture-frame button. */
@Composable
fun BackgroundPage(s: UiState, act: MainActivity) {
    val p = s.prefs
    @Suppress("UNUSED_VARIABLE") val rev = s.rev
    var checking by remember { mutableStateOf(false) }
    val apply = { Backgrounds.updateSound(act); AssistantWidget.updateAll(act); AssistantService.start(act, AssistantService.ACTION_AVATAR) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = act.displayName(uri)
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in Backgrounds.EXTENSIONS) ext = when (act.contentResolver.getType(uri)) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/bmp", "image/x-ms-bmp" -> "bmp"; "image/gif" -> "gif"; "image/webp" -> "webp"
            "image/avif" -> "avif"; "video/mp4" -> "mp4"; "video/webm" -> "webm"; else -> "" }
        if (ext.isEmpty()) { act.toast("Pick a JPG, PNG, BMP, GIF, AVIF, MP4 or WebM file"); return@rememberLauncherForActivityResult }
        val dir = File(act.filesDir, Backgrounds.DIR).apply { mkdirs() }
        val f = File(dir, "bg_${System.currentTimeMillis()}.$ext"); val old = p.bgFile
        // copy + test-decode OFF the main thread (a long video must never freeze or black-screen the app); the old
        // background stays until the new one is known to work
        checking = true
        Thread {
            val err = runCatching { act.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; Backgrounds.validate(act, f) }
                .getOrElse { it.message ?: "can't be read" }
            act.runOnUiThread {
                checking = false
                if (err == null) {
                    s.edit { bgFile = f.name; bgMode = "custom" }
                    val usedElsewhere = app.belle.assistant.ProfileStore.ids(act).any { it != p.id && app.belle.assistant.ProfileStore.prefsOf(act, it).bgFile == old }
                    if (old.isNotEmpty() && !usedElsewhere) File(dir, old).delete()
                    apply()
                } else { f.delete(); act.toast("I couldn't use that file on this ${Platform.device} ($err). Your previous background is unchanged.") }
            }
        }.start()
    }
    Section("Background for ${p.assistantName}", "Shown behind the avatar on Home, in the bubble and on the widget. Saved for this assistant only.") {
        ChoiceRow("Background", null, listOf("own" to "Keep the picture's own background", "custom" to "My background (image, GIF or video)"), p.bgMode) { v ->
            if (v == "custom" && p.bgFile.isEmpty()) pick.launch(Backgrounds.MIME_TYPES) else { s.edit { bgMode = v }; apply() } }
        ButtonRow {
            Button(onClick = { pick.launch(Backgrounds.MIME_TYPES) }) { BtnText(if (p.bgFile.isEmpty()) "Choose a file" else "Choose another file") }
            if (p.bgFile.isNotEmpty()) OutlinedButton(onClick = { s.edit { bgMode = "own" }; apply() }) { BtnText("Use the picture's own") }
        }
        if (checking) Text("Checking the file… (your current background stays until it's ready)")
        if (p.bgMode == "custom") {
            // say plainly what each surface will show — never silently ignore the chosen background
            var how by remember { mutableStateOf("") }
            LaunchedEffect(p.avatarFile, p.avatarGifMode, p.bgFile, s.rev) {
                how = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val f = p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(act.filesDir, it) }?.takeIf { it.exists() }
                    val gif = f?.extension?.lowercase() == "gif" && p.avatarGifMode != "still"
                    val b = runCatching { if (f != null) android.graphics.BitmapFactory.decodeFile(f.path) else android.graphics.BitmapFactory.decodeResource(act.resources, app.belle.assistant.R.drawable.avatar_default) }.getOrNull()
                    "2D: " + app.belle.assistant.avatar.Cutout.describe(b, gif) + "\n3D: the 3D avatar is drawn over your background (it has no background of its own)."
                }
            }
            if (how.isNotEmpty()) Help(how)
            Backgrounds.lastError?.let { Text("Last problem: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        }
        Help("JPG, PNG, BMP, GIF (animated), AVIF, MP4 or WebM. Videos and GIFs loop. On the widget they play as a slow slideshow (a few frames a second, less with lower 3D/detail settings) and stop while the screen is off.")
        if (p.bgMode == "custom") {
            var prev by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(p.bgFile) { while (true) { prev = Backgrounds.frame(act, p, System.currentTimeMillis()); kotlinx.coroutines.delay(125) } }
            prev?.let { Image(it.asImageBitmap(), "Background preview", modifier = Modifier.size(160.dp)) }
            val isVideo = Backgrounds.file(act, p)?.let(Backgrounds::isVideo) == true
            val bgFile = Backgrounds.file(act, p)
            val audio = remember(p.bgFile) { isVideo && bgFile != null && Backgrounds.hasAudio(bgFile) }
            SwitchRow("Background sound", if (!isVideo) "Only videos have sound." else if (!audio) "No audio in this file." else "Off by default. Plays the video's sound while the avatar is on screen (Home or bubble).", p.bgSound && audio) {
                if (audio) { s.edit { bgSound = it }; apply() } }
            if (p.bgSound && audio) SliderRow("Background volume", "${(p.bgVolume * 100).toInt()}%", null, p.bgVolume, 0f..1f) { v -> s.edit { bgVolume = v }; Backgrounds.updateSound(act) }
        }
    }
}
