package app.belle.assistant.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.belle.assistant.Prefs
import app.belle.assistant.core.ModelCatalog
import app.belle.assistant.core.Rss
import app.belle.assistant.core.SearchProvider
import app.belle.assistant.core.WakeWord
import app.belle.assistant.models.ModelFiles
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import java.io.File

private data class Page(val id: String, val icon: String, val title: String, val group: String, val hidden: Boolean = false, val summary: (Prefs) -> String)

/** Advanced settings, grouped: section › page › (sub-headings inside each page). */
private val PAGES = listOf(
    Page("books", "📖", "Read to me (audiobooks)", "Read to me") { _ -> AssistantRuntime.bookNow()?.first ?: "Read your EPUB, PDF and text books aloud" },
    Page("assistants", "🧑‍🤝‍🧑", "Assistants", "Assistants") { p -> "Active: ${p.assistantName}" },
    Page("wake", "👋", "Name, personality & wake words", "Listening") { p -> "${p.assistantName} · " + p.wakePhrases.take(3).joinToString(", ") { "\"$it\"" } + if (p.wakePhrases.size > 3) " +${p.wakePhrases.size - 3}" else "" },
    Page("conversation", "💬", "Continuous conversation & timeouts", "Listening") { p -> if (!p.listenTimeoutOn || p.continuous) (if (p.continuous) "Continuous conversation" else "Unlimited listening") + " · no time limit (until a sleep word or End)" else (if (p.continuous) "Continuous conversation" else "One question at a time") + " · stops after " + fmtSec(p.listenTimeoutSec) + " of silence" },
    Page("sleep", "🌙", "Sleep words", "Listening") { p -> p.sleepPhrases.take(3).joinToString(", ") { "\"$it\"" } + if (p.sleepPhrases.size > 3) " +${p.sleepPhrases.size - 3}" else "" },
    Page("voice", "🗣", "Text-to-speech", "Voice") { p -> p.voiceDir.removePrefix("vits-piper-").substringBefore("-int8") + " · speed ${"%.2f".format(p.voiceSpeed)}×" },
    Page("studio", "🎨", "Avatar Studio", "Avatar", hidden = true) { _ -> "Guided: assistant → picture → 2D or 3D → download → build" },
    Page("avatar", "✨", "2D avatar", "Avatar") { p -> (if (p.avatarFile.isEmpty()) "Built-in picture" else "Your picture") + " · mouth, joints, animation, framing" },
    Page("avatar3d", "🧊", "3D avatar", "Avatar") { p -> "Import, or make one from a photo · " + p.shader3d.label },
    Page("bubble", "🫧", "Bubble & widget", "Avatar") { p -> if (p.avatarEnabled) "Bubble " + (if (p.avatarMode == "always") "always shown" else "only while active") else "Bubble off" },
    Page("background", "🖼", "Background", "Background") { p -> if (p.bgMode == "custom") p.bgFile.substringAfterLast('.').uppercase() + (if (p.bgSound) " · sound on" else " · muted") else "The picture's own background" },
    Page("chimes", "🔔", "Chimes & sounds", "Chimes") { p -> app.belle.assistant.speech.Sounds.SLOTS.count { p.sound(it.id).isNotEmpty() }.let { if (it == 0) "Built-in chimes" else "$it custom" } },
    Page("getmodels", "⬇️", "Downloads (one tap)", "Models") { _ -> "Thinking models, speech recognition, voices, 3D generation" },
    Page("model", "🧠", "Thinking model", "Models") { p -> p.llmFile.ifEmpty { "None — simple commands only" } + if (p.loadOnDemand) " · load on demand" else "" },
    Page("memory", "🗂", "Memory", "Models") { p -> if (p.memoryEnabled) "On · only on this phone" else "Off" },
    Page("speech", "👂", "Speech recognition", "Models") { _ -> "Understanding your voice (on this phone)" },
    Page("internet", "🌐", "Internet & search", "Internet") { p -> if (p.internetEnabled) "On · " + (SearchProvider.fromId(p.searchOrder.first())?.label ?: "") else "Off (default)" },
    Page("news", "📰", "News feeds", "Internet") { p -> "${p.newsFeeds.size} feeds" },
    Page("setup", "🔐", "Permissions & background listening", "Permissions") { _ -> "Microphone, restricted settings, battery, Accessibility" },
    Page("look", "🎨", "Appearance", "Appearance & privacy") { p -> mapOf("system" to "Follow phone", "light" to "Light", "dark" to "Dark")[p.theme] + if (p.largeText) " · large text" else "" },
    Page("privacy", "🛡", "Privacy", "Appearance & privacy") { _ -> "What stays on your phone, and what doesn't" },
)

/** "Milky Moo's Assistant 0.1.1 · version code 2 · build abc1234 (release)". */
fun buildLabel(): String = "Milky Moo's Assistant ${app.belle.assistant.BuildConfig.VERSION_NAME} · version code ${app.belle.assistant.BuildConfig.VERSION_CODE} · build ${app.belle.assistant.BuildConfig.GIT_SHA}" +
    (if (app.belle.assistant.BuildConfig.DEBUG) " (debug)" else "")

fun fmtSec(s: Int) = if (s >= 60 && s % 60 == 0) "${s / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"

@Composable
fun SettingsScreen(s: UiState, act: MainActivity) {
    s.rev
    val page = PAGES.firstOrNull { it.id == s.page }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null) IconButton(onClick = { s.page = "" }) { Icon(Icons.Filled.ArrowBack, "Back") } else Spacer(Modifier.width(12.dp))
            Text(page?.title ?: "Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        // one scroll position per page (a page opens at its top); a progress block can scroll itself to the top
        val scroll = remember(page?.id) { androidx.compose.foundation.ScrollState(0) }
        val pageScroll = remember(scroll) { PageScroll(scroll) }
        // the downloads page is a long lazy list with its own scrolling
        if (page?.id == "getmodels") GetModelsPage(s, act, Modifier.weight(1f)) else
        androidx.compose.runtime.CompositionLocalProvider(LocalPageScroll provides pageScroll) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).onGloballyPositioned { pageScroll.content = it }.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            when (page?.id) {
                null -> {
                    val simple = app.belle.assistant.ProfileStore.simpleSettings(act)
                    ChoiceRow("Settings view", null, listOf("simple" to "Simple", "advanced" to "Advanced"), if (simple) "simple" else "advanced") {
                        app.belle.assistant.ProfileStore.setSimpleSettings(act, it == "simple") }
                    if (simple) SimpleSettings(s, act)
                    else PAGES.filter { !it.hidden }.groupBy { it.group }.forEach { (g, pages) ->
                        Text(g, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
                        pages.forEach { pg -> NavRow(pg.icon, pg.title, pg.summary(s.prefs)) { s.page = pg.id }; HorizontalDivider() }
                    }
                    Text(buildLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
                }
                "chimes" -> SoundsSection(s, act)
                "books" -> BooksPage(s, act)
                "assistants" -> AssistantsPage(s, act)
                "avatar3d" -> Avatar3DPage(s, act)
                "studio" -> AvatarStudioPage(s, act)
                "wake" -> WakePage(s, act)
                "sleep" -> SleepPage(s)
                "conversation" -> ConversationPage(s, act)
                "speech" -> SpeechPage(s, act)
                "voice" -> VoicePage(s, act)
                "model" -> ModelPage(s, act)
                "memory" -> MemoryPage(s, act)
                "internet" -> InternetPage(s)
                "news" -> NewsPage(s)
                "avatar" -> Avatar2DPage(s, act)
                "bubble" -> BubbleWidgetPage(s, act)
                "background" -> BackgroundPage(s, act)
                "setup" -> SetupPage(s, act)
                "look" -> LookPage(s)
                "privacy" -> PrivacyPage()
            }
        }
        }
    }
}

private fun reloadWake(act: MainActivity) = AssistantRuntime.reloadWakeWords(act)

@Composable
private fun WakePage(s: UiState, act: MainActivity) {
    val p = s.prefs
    var name by remember(p.id, p.assistantName) { mutableStateOf(p.assistantName) }
    Section("Name", "What I call myself. Changing it also offers matching wake and sleep words.") {
        FieldRow({ m -> OutlinedTextField(name, { name = it }, label = { Text("Assistant name") }, singleLine = true, modifier = m) }) {
            Button(onClick = {
                val old = p.assistantName
                s.edit {
                    assistantName = name
                    // Swap the old name for the new one inside existing phrases.
                    wakePhrases = wakePhrases.map { it.replace(Regex("\\b${Regex.escape(old.lowercase())}\\b"), assistantName.lowercase()) }
                    sleepPhrases = sleepPhrases.map { it.replace(Regex("\\b${Regex.escape(old.lowercase())}\\b"), assistantName.lowercase()) }
                }
                reloadWake(act); act.toast("Saved. Say \"${p.wakePhrases.firstOrNull()}\"")
            }) { BtnText("Save") }
        }
    }
    Section("Personality & system prompt", "Who ${p.assistantName} is, how they respond and how they address you. Each assistant has its own; reset any part to the default.") {
        SystemPromptEditor(s)
    }
    Section("Wake words", "Say any of these to wake me. Add as many as you like — single words or whole phrases. No training needed; any typed phrase works. Two or more syllables wake more reliably.") {
        PhraseList(p.wakePhrases, "Add a wake word or phrase",
            WakeWord.defaultPhrases(p.assistantName) + listOf("hey ${p.assistantName.lowercase()} are you there", "yo computer", "hello ${p.assistantName.lowercase()}"),
            onChange = { s.edit { wakePhrases = it }; reloadWake(act) },
            validate = { t -> if (t.length < 3) "Too short" else if (!t.any { it.isLetter() }) "Use letters" else null })
        AssistantRuntime.wakeRejected().takeIf { it.isNotEmpty() }?.let {
            Text("Couldn't use: ${it.joinToString()} (try spelling it like it sounds)", color = MaterialTheme.colorScheme.error)
        }
    }
    Section("Listening for wake words") {
        GatedSwitch("Wake words on", "Off = I only listen when you tap Talk, the avatar, or use the assistant gesture.", p.wakeWordEnabled,
            if (act.has(android.Manifest.permission.RECORD_AUDIO)) null else "Needs the microphone permission.", "Allow", { act.requestCorePermissions() },
            listOf("🎤 microphone", "✈️ offline", "~6 MB RAM", "Background: see Permissions")) { s.edit { wakeWordEnabled = it }; if (it) { s.edit { serviceWanted = true }; AssistantService.start(act, AssistantService.ACTION_REARM, from = "app") } }
        SliderRow("Sensitivity", "${(p.sensitivity * 100).toInt()}%", "Higher hears you more easily from across the room but wakes by mistake more often.",
            p.sensitivity, 0.05f..0.95f) { v -> p.sensitivity = v }
        TextButton(onClick = { reloadWake(act); act.toast("Sensitivity applied") }) { BtnText("Apply sensitivity") }
        ListeningStatus(act)
    }
}

private fun fmtBytes(b: Long) = if (b >= 1_000_000_000) "%.2f GB".format(b / 1e9) else "${b / 1_000_000} MB"

@Composable
private fun AssistantsPage(s: UiState, act: MainActivity) {
    s.rev
    val ctx = act
    val ids = app.belle.assistant.ProfileStore.ids(ctx)
    val active = app.belle.assistant.ProfileStore.activeId(ctx)
    var exportId by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching { act.contentResolver.openOutputStream(uri)!!.use { it.write(app.belle.assistant.ProfileStore.export(ctx, id).toByteArray()) } }
            .onSuccess { act.toast("Exported") }.onFailure { act.toast("Export failed: ${it.message}") }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { app.belle.assistant.ProfileStore.import(ctx, act.contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }
            .onSuccess { s.rev++; act.toast("Imported") }.onFailure { act.toast("Import failed: ${it.message}") }
    }
    fun activate(id: String) { app.belle.assistant.ProfileStore.setActive(ctx, id); AssistantRuntime.reloadWakeWords(ctx); s.rev++ }
    Section("Active assistant", "Each assistant has its own name, wake & sleep words, voice, avatar, model, personality and settings. The active one answers; the other settings pages edit the active one.") {
        ids.forEach { id ->
            val pp = app.belle.assistant.ProfileStore.prefsOf(ctx, id)
            Row(Modifier.fillMaxWidth().clickable { activate(id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = id == active, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(pp.assistantName, style = MaterialTheme.typography.titleMedium)
                    Text(pp.wakePhrases.take(3).joinToString(", ") { "\"$it\"" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ButtonRow {
                        TextButton(onClick = { app.belle.assistant.ProfileStore.duplicate(ctx, id); s.rev++ }) { BtnText("Copy") }
                        TextButton(onClick = { exportId = id; exporter.launch("${pp.assistantName}.belle.json") }) { BtnText("Export") }
                    }
                }
                if (ids.size > 1) IconButton(onClick = {
                    AlertDialog.Builder(act).setTitle("Delete ${pp.assistantName}?").setMessage("Its settings are removed. Shared model files stay.")
                        .setPositiveButton("Delete") { _, _ -> app.belle.assistant.ProfileStore.delete(ctx, id); AssistantRuntime.reloadWakeWords(ctx); s.rev++ }
                        .setNegativeButton("Cancel", null).show()
                }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
        var newName by remember { mutableStateOf("") }
        FieldRow({ m -> OutlinedTextField(newName, { newName = it }, label = { Text("New assistant's name") }, singleLine = true, modifier = m) }) {
            Button(enabled = newName.isNotBlank(), onClick = { app.belle.assistant.ProfileStore.create(ctx, newName.trim()); newName = ""; s.rev++ }) { BtnText("Create") }
        }
        TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { BtnText("Import an assistant (.json)") }
        Help("To rename, open Name & wake words while that assistant is active.")
    }
    Section("Several assistants listening") {
        val multi = app.belle.assistant.ProfileStore.multiWake(ctx)
        SwitchRow("Let each assistant answer its own wake words", "Off: only the active assistant listens. On: say any assistant's wake word to talk to that one. Only one ever answers at a time.", multi) {
            app.belle.assistant.ProfileStore.setMultiWake(ctx, it); AssistantRuntime.reloadWakeWords(ctx); s.rev++
        }
        if (multi) {
            Help("If two assistants share a wake word, the active one answers; otherwise the first one in this order:")
            val order = app.belle.assistant.ProfileStore.priority(ctx).filter { it in ids } + ids.filter { it !in app.belle.assistant.ProfileStore.priority(ctx) }
            order.forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${app.belle.assistant.ProfileStore.name(ctx, id)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(enabled = i > 0, onClick = { app.belle.assistant.ProfileStore.setPriority(ctx, order.toMutableList().apply { add(i - 1, removeAt(i)) }); s.rev++ }) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                    IconButton(enabled = i < order.size - 1, onClick = { app.belle.assistant.ProfileStore.setPriority(ctx, order.toMutableList().apply { add(i + 1, removeAt(i)) }); s.rev++ }) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                }
            }
        }
    }
}

@Composable
private fun SleepPage(s: UiState) {
    val p = s.prefs
    Section("Sleep words", "Say any of these to end the conversation right away. I stop listening, forget the conversation, free up memory (when \"load on demand\" is on) and go back to waiting for a wake word.") {
        PhraseList(p.sleepPhrases, "Add a sleep word or phrase",
            Prefs.defaultSleepPhrases(p.assistantName) + listOf("thanks ${p.assistantName.lowercase()}", "bye ${p.assistantName.lowercase()}", "we're done", "go away"),
            onChange = { s.edit { sleepPhrases = it } })
        Help("Tip: a sleep word also works at the end of a sentence, like \"thanks, that's all\".")
    }
    Section("When I go to sleep") {
        ChoiceRow("Confirm with", null, listOf("voice" to "Short goodbye", "chime" to "Chime only", "both" to "Both"), p.sleepConfirm) { s.edit { sleepConfirm = it } }
        Help("The avatar shows a moon-purple fade with \"z z\" when I go to sleep.")
    }
    Section("End buttons", "You can also end a conversation without speaking.") {
        Help("• Notification: \"End conversation\" appears while I'm listening or talking.\n• Avatar bubble: long-press for the menu, then End conversation.")
        SwitchRow("Tap the avatar to end", "While I'm listening or talking, one tap on the bubble ends the conversation.", p.avatarTapEnds) { s.edit { avatarTapEnds = it } }
    }
}

@Composable
private fun ConversationPage(s: UiState, act: MainActivity) {
    val p = s.prefs
    Section("Listening time", "How long I keep listening for you to start talking — after the wake word, and after each reply.") {
        SwitchRow("Unlimited listening", "On = once awake I never stop listening by myself: no silence limit and no time limit, and I keep listening after every answer. Only a sleep word or End stops me (then I go back to waiting for my wake word).", !p.listenTimeoutOn) { s.edit { listenTimeoutOn = !it } }
        val unlimited = !p.listenTimeoutOn || p.continuous
        if (!p.listenTimeoutOn) Help("⏸ The time limit below is not used while Unlimited listening is on. Turn Unlimited listening off to use it.")
        else if (p.continuous) Help("⏸ The time limit below is not used while Continuous conversation is on: I keep listening until you say a sleep word or press End. Turn Continuous conversation off to use it.")
        Column(Modifier.alpha(if (unlimited) 0.38f else 1f)) {
            val minutes = p.listenTimeoutUnit == "m"
            var text by remember(p.listenTimeoutUnit) { mutableStateOf(if (minutes) (p.listenTimeoutSec / 60).coerceAtLeast(1).toString() else p.listenTimeoutSec.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { v ->
                    text = v.filter { it.isDigit() }.take(4)
                    text.toIntOrNull()?.let { n -> s.edit { listenTimeoutSec = if (minutes) n * 60 else n } }
                }, label = { Text("Wait for") }, singleLine = true, enabled = !unlimited, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp))
                Spacer(Modifier.width(12.dp))
                ChoiceRow("", null, listOf("s" to "seconds", "m" to "minutes"), p.listenTimeoutUnit) { u ->
                    if (!unlimited) s.edit {
                        listenTimeoutUnit = u
                        if (u == "m" && listenTimeoutSec < 60) listenTimeoutSec = 60
                    }
                }
            }
            Help(if (unlimited) "Saved: ${fmtSec(p.listenTimeoutSec)} (not used while " + (if (!p.listenTimeoutOn) "Unlimited listening" else "Continuous conversation") + " is on)." else "Now: ${fmtSec(p.listenTimeoutSec)}. When it runs out I go back to waiting for my wake word.")
        }
    }
    Section("Conversation") {
        GatedSwitch("Continuous conversation", (if (!p.listenTimeoutOn) "(Unlimited listening is on, so I always keep listening after answers.) " else "") + "After I answer, I keep listening so you can just keep talking — no wake word needed — until you say a sleep word or press End — no time limits apply while it's on. I remember the last few things we said, so follow-ups like \"and tomorrow?\" work.", p.continuous,
            if (ModelFiles.hasStt(act)) null else "Needs speech recognition (one-tap download).", "Get it", { s.page = "getmodels" },
            listOf("Saved per assistant", "Also on the widget")) { s.edit { continuous = it }; AssistantWidget.updateAll(act) }
        SwitchRow("Ask before sending messages", "I read back texts and wait for \"yes\" before sending.", p.confirmMessages) { s.edit { confirmMessages = it } }
        Help("I always answer out loud.")
    }
    Section("Sounds") {
        SwitchRow("Chime when I start listening", null, p.chime) { s.edit { chime = it } }
        SwitchRow("Chime when I stop listening", null, p.stopChime) { s.edit { stopChime = it } }
    }
    Section("Chimes", "Pick phone sounds or your own files for the start/stop chimes.") { TextButton(onClick = { s.page = "chimes" }) { BtnText("Chimes & sounds ›") } }
}

/** Every sound slot: the built-in chime, one of the phone's own sounds, or an imported file; with preview and reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundsSection(s: UiState, act: MainActivity) {
    val p = s.prefs
    var target by remember { mutableStateOf("") }
    val ringPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val uri = r.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == android.app.Activity.RESULT_OK && target.isNotEmpty()) {
            if (uri == null) app.belle.assistant.speech.Sounds.reset(act, p, target) else s.edit { setSound(target, "ring:$uri") }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && target.isNotEmpty()) {
            app.belle.assistant.speech.Sounds.import(act, p.id, target, uri, act.displayName(uri))
                .onSuccess { v -> s.edit { setSound(target, v) }; act.toast("Sound imported") }
                .onFailure { act.toast("Couldn't use that file: ${it.message}") }
        }
    }
    Section("Chime sounds", "Pick one of your phone's own sounds or import any sound file (MP3, OGG, WAV, M4A, FLAC, OPUS…) for each moment. Imported files are copied into the app. Sounds longer than 8 seconds are cut off. Saved per assistant.") {
        app.belle.assistant.speech.Sounds.SLOTS.forEach { slot ->
            val v = p.sound(slot.id)
            Text(slot.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text(app.belle.assistant.speech.Sounds.describe(act, v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    target = slot.id
                    ringPicker.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, slot.title)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (v.startsWith("ring:")) Uri.parse(v.removePrefix("ring:")) else null))
                }) { BtnText("Phone sound") }
                OutlinedButton(onClick = { target = slot.id; filePicker.launch(arrayOf("audio/*", "application/ogg", "video/mp4", "video/webm")) }) { BtnText("Import file") }
                OutlinedButton(onClick = { val cur = p.sound(slot.id); val vol = p.voiceVolume; Thread { app.belle.assistant.speech.Sounds.play(act, cur, slot.id, vol) }.start() }) { BtnText("Preview") }
                if (v.isNotEmpty()) TextButton(onClick = { app.belle.assistant.speech.Sounds.reset(act, p, slot.id) }) { BtnText("Reset") }
            }
        }
    }
}

@Composable
private fun SpeechPage(s: UiState, act: MainActivity) {
    s.rev
    val ok = ModelFiles.hasStt(act)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(act::importFile) }
    Section("Speech recognition", "Turns your voice into text, entirely on this phone. Not included in the app download to keep it small — add it once.") {
        Text(if (ok) "✅ Installed: Moonshine Tiny (English) + Silero voice detector" else "⚠️ Not installed yet — I can hear my wake words but not what you say after.",
            style = MaterialTheme.typography.bodyLarge)
        Help("You need two files:\n• ${ModelCatalog.STT.fileName} (${ModelCatalog.STT.bytes / 1_000_000} MB)\n• ${ModelCatalog.VAD.fileName} (under 1 MB)\nGet both with one tap on the Downloads page, or download them on any computer from the sherpa-onnx releases page, copy them to the phone and import them here.")
        ButtonRow {
            Button(onClick = { s.page = "getmodels" }) { BtnText("One-tap download") }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { BtnText("Import files") }
            if (downloaderInstalled(act)) TextButton(onClick = { openDownloader(act) }) { BtnText("Open Downloader") }
        }
        s.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun VoicePage(s: UiState, act: MainActivity) {
    val p = s.prefs
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(act::importFile) }
    SpeakerPicker(s, act)   // first, so a multi-speaker voice's speakers aren't below the long voice list
    Section("Text-to-speech › Voice model", "Every voice runs on this phone. ▶ plays installed voices live; others play the official sample. Tap an installed voice to use it.") {
        if (ModelFiles.installedVoices(act).isEmpty()) Blocked("No voice installed yet: I show replies as text only.", "Get a voice") { s.page = "getmodels" }
        VoiceList(s, act)
        ButtonRow {
            Button(onClick = { s.page = "getmodels" }) { BtnText("One-tap downloads") }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { BtnText("Import voice file") }
        }
        Help("Standard = int8 (small, fast). High = bigger Piper model, clearer. Full precision = same voice without int8 rounding (smoothest, ~3–4× size). Kokoro = most natural, slower.")
    }
    Section("Text-to-speech › Quality") {
        SliderRow("Speed", "${"%.2f".format(p.voiceSpeed)}×", "Talking speed (the model's length scale).", p.voiceSpeed, 0.6f..1.6f) { v -> p.voiceSpeed = v }
        SliderRow("Volume", "${(p.voiceVolume * 100).toInt()}%", "How loud I talk, on top of the phone's own volume. Saved for ${p.assistantName}.", p.voiceVolume, 0f..1f) { v -> s.edit { voiceVolume = v } }
        val shift = p.voiceShift(p.voiceDir)
        SliderRow("Masculine ↔ Feminine", when { shift < -0.05f -> "Deeper ${"%.0f".format(-shift * 100)}%"; shift > 0.05f -> "Lighter ${"%.0f".format(shift * 100)}%"; else -> "Natural" },
            "Moves pitch and voice character together. Leave at Natural for the cleanest sound. Saved for this voice.", shift, -1f..1f) { v -> s.edit { setVoiceShift(voiceDir, v) } }
        var adv by remember { mutableStateOf(false) }
        TextButton(onClick = { adv = !adv }) { BtnText(if (adv) "Hide advanced quality" else "Advanced quality ›") }
        if (adv) {
            SubHeader("Advanced (Piper / VITS voices)")
            SliderRow("Expressiveness", "%.2f".format(p.ttsNoise), "noise_scale. Default 0.667. Lower = flatter but cleaner; higher = livelier but can sound rough.", p.ttsNoise, 0.1f..1.0f) { v -> p.ttsNoise = v; AssistantRuntime.reloadVoice() }
            SliderRow("Rhythm variation", "%.2f".format(p.ttsNoiseW), "noise_w. Default 0.8. Lower = steadier timing.", p.ttsNoiseW, 0.1f..1.2f) { v -> p.ttsNoiseW = v; AssistantRuntime.reloadVoice() }
            ButtonRow {
                OutlinedButton(onClick = { AssistantRuntime.previewVoice(act, p.voiceDir, p.previewPhrase) }) { BtnText("▶ Preview") }
                TextButton(onClick = { p.ttsNoise = 0.667f; p.ttsNoiseW = 0.8f; s.edit { voiceSpeed = 1f; setVoiceShift(voiceDir, 0f) }; AssistantRuntime.reloadVoice() }) { BtnText("Reset to defaults") }
            }
        }
    }
}

/** Multi-speaker voices (Kokoro: 11 named speakers; LibriTTS: 904 numbered ones): pick who talks. Saved per voice. */
@Composable
private fun SpeakerPicker(s: UiState, act: MainActivity) {
    val p = s.prefs
    val v = ModelCatalog.VOICES.firstOrNull { it.dirName == p.voiceDir } ?: return
    if (v.speakers <= 1) return
    val cur = p.speakerFor(v.dirName).coerceIn(0, v.speakers - 1)
    fun pick(i: Int) { s.edit { setSpeakerFor(v.dirName, i) }; AssistantRuntime.previewVoice(act, v.dirName, p.previewPhrase, i) }
    Section("Text-to-speech › Speaker", "${v.label.substringBefore(" (")} has ${v.speakers} speakers. Picking one plays it; it's saved for this voice.") {
        if (v.speakers <= 20) {
            (0 until v.speakers).forEach { i ->
                Row(Modifier.fillMaxWidth().clickable { pick(i) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = i == cur, onClick = null)
                    Text(v.speakerLabel(i), Modifier.padding(start = 8.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick((cur - 1 + v.speakers) % v.speakers) }) { BtnText("‹") }
                Text("Speaker $cur of ${v.speakers - 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { pick((cur + 1) % v.speakers) }) { BtnText("›") }
            }
            SliderRow("Speaker number", "$cur", null, cur.toFloat(), 0f..(v.speakers - 1).toFloat()) { f -> s.edit { setSpeakerFor(v.dirName, f.toInt()) } }
            ButtonRow {
                OutlinedButton(onClick = { AssistantRuntime.previewVoice(act, v.dirName, p.previewPhrase, cur) }) { BtnText("▶ Hear speaker $cur") }
                TextButton(onClick = { pick((0 until v.speakers).random()) }) { BtnText("Random") }
            }
        }
    }
}

private fun voiceOf(d: app.belle.assistant.models.Dl) = if (d.kind != "voice") null else app.belle.assistant.core.ModelCatalog.VOICES.firstOrNull { "voice:" + it.dirName == d.id }

/** The optional separate "Belle Downloader" app; its button is only shown when it is installed. */
private fun downloaderInstalled(act: MainActivity) = act.packageManager.getLaunchIntentForPackage("app.belle.downloader") != null
private fun openDownloader(act: MainActivity) {
    act.packageManager.getLaunchIntentForPackage("app.belle.downloader")?.let(act::startActivity)
}

@Composable
private fun ModelPage(s: UiState, act: MainActivity) {
    val p = s.prefs
    val ram = remember {
        val mi = ActivityManager.MemoryInfo(); act.getSystemService(ActivityManager::class.java).getMemoryInfo(mi); mi.totalMem / 1073741824.0
    }
    val rec = ModelCatalog.recommendedLlm(ram)
    val installed = ModelFiles.installedLlms(act)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(act::importFile) }
    Section("Memory", "Long-term conversation memory, kept only on this phone.") { MemorySimple(s, act) }
    Section("Model", "A small language model lets me understand everyday speech and answer questions. Simple commands (timers, calls, flashlight…) work even without one.") {
        if (installed.isEmpty()) Help("No model installed yet.")
        // say exactly what will run when the choice and the files disagree (missing file, profile without a choice)
        app.belle.assistant.core.ModelChoice.resolve(p.llmFile, app.belle.assistant.ProfileStore.lastLlm(act), installed.map { it.name }).let { pk ->
            if (pk.note != "chosen for this assistant" && (installed.isNotEmpty() || p.llmFile.isNotEmpty())) {
                Text("ℹ️ " + pk.note.replaceFirstChar { it.uppercase() } + (pk.file?.let { " ($it)." } ?: "."), color = MaterialTheme.colorScheme.tertiary)
                if (pk.file != null) OutlinedButton(onClick = { s.edit { llmFile = pk.file }; AssistantRuntime.reloadLlm(act) }) { BtnText("Use ${pk.file} for ${p.assistantName}") }
                else OutlinedButton(onClick = { s.page = "getmodels" }) { BtnText("Download a thinking model") }
            }
        }
        installed.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { s.edit { llmFile = f.name }; AssistantRuntime.reloadLlm(act) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.llmFile == f.name, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    val m = ModelCatalog.LLMS.firstOrNull { it.fileName == f.name }
                    Text(m?.label ?: f.name, style = MaterialTheme.typography.titleMedium)
                    if (m != null) Text(m.censorLabel + if (m.roleplay) " · Roleplay / story persona" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${f.length() / 1_000_000} MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    AlertDialog.Builder(act).setTitle("Delete ${f.name}?").setPositiveButton("Delete") { _, _ ->
                        if (p.llmFile == f.name) { s.edit { llmFile = "" }; AssistantRuntime.reloadLlm(act) }
                        f.delete(); s.rev++
                    }.setNegativeButton("Cancel", null).show()
                }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
        Text("This phone has %.0f GB of memory. Recommended: %s".format(ram, rec.label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        ModelCatalog.LLMS.forEach { m ->
            Text("• ${m.label} — ${m.bytes / 1_000_000} MB, needs ${m.minRamGb} GB+ RAM. ${m.note}", style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (m.id == rec.id) FontWeight.SemiBold else FontWeight.Normal)
        }
        ButtonRow {
            Button(onClick = { s.page = "getmodels" }) { BtnText("One-tap download") }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { BtnText("Import .gguf file") }
            if (downloaderInstalled(act)) TextButton(onClick = { openDownloader(act) }) { BtnText("Open Downloader") }
        }
        s.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
    Section("Memory & speed") {
        SwitchRow("Load assistant on demand", "Recommended. Only the tiny wake-word listener stays in memory. Speech, voice and the model load when you wake me (about 1–3 s) and unload when idle — saves memory and battery.",
            p.loadOnDemand) { s.edit { loadOnDemand = it }; AssistantRuntime.applyLoadOnDemand(act) }
        if (p.loadOnDemand) SliderRow("Unload after idle", fmtSec(p.unloadAfterSec), null, p.unloadAfterSec.toFloat(), 10f..600f) { v -> s.edit { unloadAfterSec = (v / 10).toInt() * 10 } }
        SliderRow("Processor threads", if (p.llmThreads == 0) "Auto" else "${p.llmThreads}", "Auto picks the phone's fast cores.", p.llmThreads.toFloat(), 0f..8f, steps = 7) { v -> s.edit { llmThreads = v.toInt() } }
        Text(AssistantRuntime.loadState(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ButtonRow {
            OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "unload"); act.toast("Book unloaded. Your place is saved.") }) { BtnText("Unload the book") }
            OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "unloadAll"); act.toast("Unloading the book, voice, speech and model. Nothing is deleted.") }) { BtnText("Unload everything") }
        }
        Text("Frees memory now. No books, bookmarks, models or settings are deleted; each part loads again when it's next needed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InternetPage(s: UiState) {
    val p = s.prefs
    Section("Internet access", "Off by default. When on, I can look things up and read the news. Nothing else ever goes online — no accounts, tracking or analytics.") {
        SwitchRow("Allow internet access", if (p.internetEnabled) "On — searches go to the providers below." else "Off — everything stays on this phone.", p.internetEnabled) { s.edit { internetEnabled = it } }
        Requires("🌐 needs internet", "Off = fully offline")
        Help("Note: Android can't remove the internet permission from a single app. This switch blocks every request inside the app. For a hard guarantee, use a firewall app like NetGuard or RethinkDNS and block ${p.assistantName} (and allow it only when you want).")
    }
    if (!p.internetEnabled) return
    Section("Browsing mode", "How I visit search sites. No accounts or API keys are ever needed.") {
        ChoiceRow("Mode", null, listOf("headless" to "Headless", "frontend" to "Frontend"), p.browsingMode) { s.edit { browsingMode = it } }
        Help(if (p.browsingMode == "headless")
            "Headless: a hidden browser inside the app opens the real search page — just like you would — reads the results and answers out loud. Nothing pops up on screen."
        else "Frontend: the search also opens as a new tab in your own browser so you can see it, while the hidden reader answers out loud at the same time.")
        if (p.browsingMode == "frontend") SwitchRow("Also read the browser tab", "Uses Accessibility (lets me tap/scroll/read the screen) to read what's in the tab if the hidden reader gets nothing.", p.frontendScreenRead) { s.edit { frontendScreenRead = it } }
        if (p.browsingMode == "frontend") Requires("Needs Accessibility (lets me tap/scroll/read the screen) — Permissions page", "🌐 internet")
        Help("Why this works: searches come from your phone's real browser engine and your own home or mobile connection, like any person browsing, so sites rarely block them (\"too many requests\" / captcha). If one site does ask for a captcha, I quietly try the next one.")
        TextButton(onClick = { AssistantRuntime.webView.clear(); }) { BtnText("Clear browsing data") }
    }
    Section("Search providers", "I try them top to bottom and use the first that answers. The top one is your default. No Google.") {
        val order = p.searchOrder.mapNotNull { SearchProvider.fromId(it) }.let { o -> o + SearchProvider.values().filter { it !in o } }
        order.forEachIndexed { i, sp ->
            val on = sp.id !in p.searchDisabled
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(sp.label + if (i == 0) "  (default)" else "", style = MaterialTheme.typography.titleMedium)
                    Text(sp.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sp == SearchProvider.SEARXNG && p.searxUrl.isBlank()) Text("Skipped until you add a server address below.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(enabled = i > 0, onClick = { s.edit { searchOrder = order.map { it.id }.toMutableList().apply { add(i - 1, removeAt(i)) } } }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                IconButton(enabled = i < order.size - 1, onClick = { s.edit { searchOrder = order.map { it.id }.toMutableList().apply { add(i + 1, removeAt(i)) } } }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                Switch(on, { v -> s.edit { searchDisabled = if (v) searchDisabled - sp.id else searchDisabled + sp.id } })
            }
            HorizontalDivider()
        }
    }
    Section("SearXNG server (optional)", "Your own or a trusted instance, with JSON output enabled. Home-network addresses may use http.") {
        var url by remember(p.id, p.searxUrl) { mutableStateOf(p.searxUrl) }
        OutlinedTextField(url, { url = it; p.searxUrl = it }, label = { Text("https://searx.example.org") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Section("Answers") {
        SliderRow("Pages to read per question", "${p.pagesToRead}", "More pages = better checked answers, but slower.", p.pagesToRead.toFloat(), 1f..5f, steps = 3) { v -> s.edit { pagesToRead = v.toInt() } }
        Help("I read the top pages, write a short answer with numbered sources like [1], say it out loud, and list the sources in the Conversation tab.")
    }
}

@Composable
private fun NewsPage(s: UiState) {
    val p = s.prefs
    Section("News feeds", "Say \"what's the news\" or \"latest news about storms\". I read headlines from these RSS feeds. Needs Internet access on.") {
        p.newsFeeds.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.substringBefore('|'), style = MaterialTheme.typography.titleMedium)
                    Text(line.substringAfter('|'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { s.edit { newsFeeds = newsFeeds - line } }) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        OutlinedTextField(name, { name = it }, label = { Text("Feed name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Feed address (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ButtonRow {
            Button(enabled = url.startsWith("http"), onClick = {
                s.edit { newsFeeds = newsFeeds + "${name.ifBlank { url.substringAfter("://").substringBefore('/') }}|${url.trim()}" }; name = ""; url = ""
            }) { BtnText("Add feed") }
            OutlinedButton(onClick = { s.edit { newsFeeds = Rss.DEFAULT_FEEDS } }) { BtnText("Reset to defaults") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPage(s: UiState, act: MainActivity) {
    s.rev
    val p = s.prefs
    @Composable
    fun Item(title: String, help: String, done: Boolean?, button: String, go: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (done) { true -> "✅"; false -> "⚠️"; null -> "•" }, modifier = Modifier.width(36.dp), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done != true) FilledTonalButton(onClick = go) { BtnText(button) }
        }
    }
    val rm = act.getSystemService(RoleManager::class.java)
    val isAssistant = rm?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val pm = act.getSystemService(PowerManager::class.java)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
    if (Build.VERSION.SDK_INT >= 33) Section("Allow restricted settings", "Android blocks a few sensitive switches for apps installed from a file (not from a store) until you allow them once: Accessibility and notification access, and on some phones SMS too. If Android says \"Restricted setting\" when you turn one of those on, do this once:") {
        Help("1. Tap Open app info below.\n2. Tap ⋮ (top right) › Allow restricted settings, and confirm with your PIN or fingerprint.\n3. Come back and turn on Accessibility / allow notifications again.\nThe ⋮ menu only shows the option after Android has blocked one of those switches once; if it isn't there, try turning the switch on first.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { act.startActivity(details); act.toast("⋮ (top right) › Allow restricted settings") }) { BtnText("Open app info") }
            OutlinedButton(onClick = { openAccessibilitySettings(act, p.assistantName) }) { BtnText("Accessibility") }
            OutlinedButton(onClick = { act.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)) }) { BtnText("Notifications") }
        }
    }
    Section("Keep listening in the background", "So wake words work with the screen off and inside other apps, Android and Samsung's battery savers must leave me running.") {
        Item("Battery optimization: unrestricted", "Lets the wake word keep running when the phone is idle.", pm.isIgnoringBatteryOptimizations(act.packageName), "Allow") {
            if (runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }.isFailure) act.startActivity(details)
        }
        Item("Samsung: Never sleeping apps", "Settings › Battery › Background usage limits › Never sleeping apps › + › ${p.assistantName}. Also turn off \"Put unused apps to sleep\".", null, "Open") {
            val tries = listOf(
                Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent("com.samsung.android.sm.ACTION_BATTERY"),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details)
            for (i in tries) if (runCatching { act.startActivity(i) }.isSuccess) break
        }
        SwitchRow("Keep the processor awake while listening", "Recommended. Without it some phones stop checking for wake words a few minutes after the screen goes off.", p.keepCpuAwake) {
            s.edit { keepCpuAwake = it }; if (app.belle.assistant.service.AssistantService.inForeground) AssistantService.start(act, AssistantService.ACTION_AVATAR)
        }
        if (AssistantRuntime.micSilenced) Text("⚠️ Android is blocking the microphone right now (another app is recording, or listening was started in the background after a restart). Open the app once to fix it.", color = MaterialTheme.colorScheme.error)
    }
    Section("Permissions") {
        Item("Microphone", "To hear wake words and you. Audio never leaves the phone.", act.has(android.Manifest.permission.RECORD_AUDIO), "Allow") { act.requestCorePermissions() }
        Item("Calls, texts & contacts", "So \"call Mom\" and \"text Sam\" work.", act.corePermissions().all { act.has(it) }, "Allow") { act.requestCorePermissions() }
        Item("Display over other apps", "For the floating avatar and edge glow.", Settings.canDrawOverlays(act), "Allow") {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = act.getSystemService(android.app.AlarmManager::class.java)
            Item("Exact reminders", "So reminders fire on time.", am.canScheduleExactAlarms(), "Allow") {
                act.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${act.packageName}")))
            }
        }
    }
    Section("Phone setup") {
        Item("Default assistant", "Long-press Home / the side key opens me instead of Google or Bixby.", isAssistant, "Set") {
            for (a in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", Settings.ACTION_SETTINGS)) {
                if (runCatching { act.startActivity(Intent(a)) }.isSuccess) break
            }
            act.toast("Choose Digital assistant app → ${p.assistantName}")
        }
        Item(A11Y_TITLE, "$A11Y_WHY\nHow: ${a11ySteps(p.assistantName)}", app.belle.assistant.actions.BelleAccessibilityService.instance != null, "Turn on") {
            openAccessibilitySettings(act, p.assistantName)
        }
        Item("Keep running in the background", "Stops battery saver from shutting off the wake word.", pm.isIgnoringBatteryOptimizations(act.packageName), "Allow") {
            if (runCatching { act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))) }.isFailure) act.startActivity(details)
        }
        Item("App info", "All of Android's settings for this app.", null, "Open") { act.startActivity(details) }
    }
    Section(if (Build.MANUFACTURER.equals("samsung", true)) "Samsung / One UI tips" else "Tips for Samsung and other phones") {
        Help("""
            • Accessibility says "Restricted setting"? Settings › Apps › ${p.assistantName} › ⋮ (top right) › Allow restricted settings, then turn Accessibility on again.
            • Keep me awake: Settings › Battery › Background usage limits › Never sleeping apps › add ${p.assistantName}. Also Apps › ${p.assistantName} › Battery › Unrestricted.
            • Side key: Settings › Advanced features › Side button › Press and hold › Digital assistant (after making me the default), or Double press › Open app › ${p.assistantName}.
            • Turn off Bixby Voice wake-up so it doesn't compete for the microphone.
            • Other brands (Oukitel, MediaTek phones): look for Battery › App launch / Background management and allow ${p.assistantName} to run in the background.
            • After restarting the phone, Android 14+ won't let any app turn on the microphone in the background — open the app (or tap Talk) once.
        """.trimIndent())
    }
}

@Composable
private fun LookPage(s: UiState) {
    val p = s.prefs
    Section("Appearance") {
        ChoiceRow("Theme", null, listOf("dark" to "Dark (default)", "light" to "Light", "system" to "Follow phone"), p.theme) { s.edit { theme = it } }
        GatedSwitch("Wallpaper colours", "Use colours from your wallpaper. Off = the app's own high-contrast colours.", p.wallpaperColors,
            if (android.os.Build.VERSION.SDK_INT >= 31) null else "Needs Android 12 or newer.", null, null, listOf("Android 12+")) { s.edit { wallpaperColors = it } }
        SwitchRow("Large text", "Makes all text in the app bigger.", p.largeText) { s.edit { largeText = it } }
    }
}

@Composable
private fun PrivacyPage() {
    Section("About this build") {
        Text(buildLabel(), style = MaterialTheme.typography.bodyLarge)
        Help("Compare this with the APK file name (MilkyMoosAssistant-<version>-<build>-….apk) to check which build is installed.")
    }
    Section("Diagnostics", "For reporting a problem: nothing is sent; you choose where the file goes.") {
        (androidx.compose.ui.platform.LocalContext.current as? MainActivity)?.let { ExportLogButtons(it) }
    }
    Section("What stays on this phone") {
        Help("""
            • Wake words, speech recognition, the voice and the language model all run on this phone. Audio is never recorded to storage or sent anywhere.
            • No Google Play Services, Firebase, analytics, ads, crash reporters, or accounts. Settings are stored only in this app's private storage and excluded from cloud backup.
            • The conversation history lives in memory only and is cleared when the app closes.
        """.trimIndent())
    }
    Section("What can go online (only if you allow it)") {
        Help("""
            • Internet access is OFF by default. When you turn it on, only web searches and news feeds you ask for are fetched, from the providers you choose (DuckDuckGo, Brave, SearXNG, Wikipedia — never Google). No third-party cookies; first-party cookies stay in this app until you press Clear browsing data (Internet & search). No identifiers are sent.
            • Model downloads happen only when you tap Download (Downloads page) or Install 3D generation (3D avatar page): from Hugging Face / GitHub only, every file SHA-256 checked. You can also copy files to the phone and import them instead.
            • ▶ Play sample on a voice you haven't installed fetches that short sample once from the Piper project's page.
            • Android can't remove the internet permission from one app. For a hard guarantee, block this app in a firewall such as NetGuard or RethinkDNS.
        """.trimIndent())
    }
}
