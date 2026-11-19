package app.belle.assistant.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.belle.assistant.R
import app.belle.assistant.models.ModelFiles
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantRuntime.Mode
import app.belle.assistant.service.AssistantRuntime.Ui
import app.belle.assistant.service.AssistantService
import java.text.DateFormat
import java.util.Date

@Composable
fun stateColor(ui: Ui): Color = when (ui) {
    Ui.LISTENING -> Color(0xFF4FC3F7)
    Ui.WAKING -> Color(0xFFB39DDB)
    Ui.THINKING -> Color(0xFFFFB74D)
    Ui.SPEAKING -> Color(0xFF81C784)
    Ui.SLEEPING -> Color(0xFF7E57C2)
    Ui.IDLE -> MaterialTheme.colorScheme.outlineVariant
}

fun stateLabel(ui: Ui, mode: Mode): String = when {
    ui == Ui.WAKING -> "Waking up…"
    ui == Ui.LISTENING -> "Listening…"
    ui == Ui.THINKING -> "Thinking…"
    ui == Ui.SPEAKING -> "Speaking"
    ui == Ui.SLEEPING -> "Going to sleep"
    mode == Mode.OFF -> "Off"
    mode == Mode.LOADING -> "Getting ready…"
    else -> "Ready"
}

/** Big avatar with a pulsing ring in the state's color. */
@Composable
fun StateOrb(ui: Ui, size: Int = 260) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.92f, 1.06f,
        infiniteRepeatable(tween(if (ui == Ui.SPEAKING) 260 else 800), RepeatMode.Reverse), label = "p")
    val c = stateColor(ui)
    val tapCtx = androidx.compose.ui.platform.LocalContext.current
    // the whole circle (avatar, its background and the ring) is the tap target: talk, or end a conversation
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).semantics { contentDescription = "Avatar. Tap to talk." }.pointerInput(Unit) {
        detectTapGestures { if (AssistantRuntime.inSession) AssistantRuntime.endSession(tapCtx, "avatar tap") else AssistantService.start(tapCtx, AssistantService.ACTION_LISTEN, from = "app") }
    }) {
        Box(Modifier.size((size * 0.92).dp).scale(if (ui == Ui.IDLE) 1f else pulse).clip(androidx.compose.foundation.shape.RoundedCornerShape(30)).background(c.copy(alpha = if (ui == Ui.IDLE) 0.10f else 0.22f)))
        // The real avatar (talking + moving), framed with the Home framing.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { app.belle.assistant.avatar.AvatarView(it, "home").apply { setImageFile(avatarFileOf(ctx)); isClickable = false; isFocusable = false } },
            // re-read the picture when the framing changes, the active assistant changes, or their picture is replaced
            update = { v -> v.state = ui; val f = avatarFileOf(ctx)
                val key = "${FramingRev.rev}|${app.belle.assistant.ProfileStore.currentId(ctx)}|${f?.path}|${f?.lastModified()}"
                if (v.tag != key) { v.tag = key; v.setImageFile(f) } },
            modifier = Modifier.size((size * 1.0).dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(s: UiState, act: MainActivity) {
    s.tick; s.rev
    val p = s.prefs
    val ui = AssistantRuntime.ui
    val mode = AssistantRuntime.mode
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.assistantName, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Private voice assistant · runs on this phone", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        StateOrb(ui)
        Spacer(Modifier.height(12.dp))
        Text(stateLabel(ui, mode), style = MaterialTheme.typography.headlineSmall, color = if (ui == Ui.IDLE) MaterialTheme.colorScheme.onSurface else stateColor(ui))
        Text(AssistantRuntime.status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(20.dp))

        if (mode == Mode.OFF) {
            Button(onClick = { act.startAssistant() }, modifier = Modifier.fillMaxWidth().height(60.dp)) { BtnText("Start listening for \"${p.wakePhrases.firstOrNull() ?: "hey " + p.assistantName}\"", style = MaterialTheme.typography.titleMedium) }
        } else if (AssistantRuntime.inSession) {
            Button(onClick = { AssistantRuntime.endSession(act, "home screen") }, modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { BtnText("End conversation", style = MaterialTheme.typography.titleMedium) }
        } else {
            Button(onClick = { AssistantService.start(act, AssistantService.ACTION_LISTEN) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Icon(painterResource(R.drawable.ic_mic), null); Spacer(Modifier.size(8.dp)); Text("Talk now", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (mode != Mode.OFF) TextButton(onClick = { act.startService(Intent(act, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)) }) {
            BtnText("Turn off listening")
        }
        // "Read to me": the open book, or a way in
        val book = AssistantRuntime.bookNow()
        OutlinedButton(onClick = { s.tab = "settings"; s.page = "books" }, modifier = Modifier.fillMaxWidth()) {
            BtnText(book?.let { (line, playing) -> (if (playing) "📖 Reading: " else "📖 Paused: ") + line } ?: "📖 Read to me")
        }
        if (book != null) TextButton(onClick = { AssistantRuntime.bookControl(act, "toggle") }) { BtnText(if (book.second) "Pause the book" else "Play the book") }

        // Setup checklist: only shows what still needs doing.
        val todo = mutableListOf<Pair<String, () -> Unit>>()
        if (!act.has(android.Manifest.permission.RECORD_AUDIO)) todo += "Allow the microphone" to { act.requestCorePermissions() }
        if (!ModelFiles.hasStt(act)) todo += "Add speech recognition so I can understand your voice (30 MB)" to { s.tab = "settings"; s.page = "getmodels" }
        if (ModelFiles.installedVoices(act).isEmpty()) todo += "Add a voice so I can talk back (about 20 MB)" to { s.tab = "settings"; s.page = "getmodels" }
        if (ModelFiles.installedLlms(act).isEmpty()) todo += "Add a language model so I understand more than simple commands" to { s.tab = "settings"; s.page = "getmodels" }
        if (!Settings.canDrawOverlays(act)) todo += "Allow \"Display over other apps\" so I can open apps by voice" + (if (p.avatarEnabled) " and show the floating avatar" else "") to {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
        }
        if (todo.isNotEmpty()) {
            Section("Finish setting up") {
                todo.forEach { (label, go) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("• $label", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = go) { BtnText("Fix") }
                    }
                }
            }
        }

        WidgetHint(s, act)

        Section("What you can say", "Start with a wake word, then just talk normally. Say a sleep word to end.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("set a timer for 10 minutes", "call mom", "text Sam I'm on my way", "turn on the flashlight", "open Spotify",
                    "remind me to take my pills at 8", "what's the latest news", "look up how tall Everest is", p.sleepPhrases.firstOrNull() ?: "goodnight")
                    .forEach { AssistChip(onClick = { AssistantRuntime.handleText(act, it) }, label = { Text("\"$it\"") }) }
            }
        }

        Section("Memory use") {
            Text(AssistantRuntime.loadState(), style = MaterialTheme.typography.bodyMedium)
            Help(if (p.loadOnDemand) "Only the tiny wake-word listener stays loaded. Everything else loads when you wake me and unloads after ${p.unloadAfterSec}s idle."
                 else "Everything stays loaded for the fastest replies (uses more memory and battery).")
        }
        Section("Internet") {
            Text(if (p.internetEnabled) "On — I can look things up and read the news (${p.searchOrder.firstOrNull() ?: "search"} first)." else "Off — nothing leaves this phone.",
                style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { s.tab = "settings"; s.page = "internet" }) { BtnText("Change") }
        }
        s.busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun ChatScreen(s: UiState, act: MainActivity) {
    s.tick
    val turns = synchronized(AssistantRuntime.transcript) { AssistantRuntime.transcript.toList() }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1) }
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        // title left, buttons right; with large text the buttons move under the title instead of squeezing it
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column {
                Text("Conversation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stateLabel(AssistantRuntime.ui, AssistantRuntime.mode), color = stateColor(AssistantRuntime.ui).takeIf { AssistantRuntime.ui != Ui.IDLE } ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (AssistantRuntime.inSession) OutlinedButton(onClick = { AssistantRuntime.endSession(act, "conversation screen") }) { BtnText("End") }
                if (turns.isNotEmpty()) TextButton(onClick = { AssistantRuntime.clearTranscript() }) { BtnText("Clear") }
            }
        }
        if (turns.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nothing here yet.\nSay \"${s.prefs.wakePhrases.firstOrNull() ?: "hey belle"}\" or type below.\nThis history stays in memory only and is gone when the app closes.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(turns) { t -> Bubble(t, act) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("Type a message…") }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { AssistantRuntime.handleText(act, input); input = "" } }))
            if (input.isNotBlank()) IconButton(onClick = { AssistantRuntime.handleText(act, input); input = "" }) { Icon(Icons.Filled.Send, "Send") }
            else IconButton(onClick = { AssistantService.start(act, AssistantService.ACTION_LISTEN) }) { Icon(painterResource(R.drawable.ic_mic), "Talk") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(t: AssistantRuntime.Turn, act: MainActivity) {
    val me = t.fromUser
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
        Card(shape = RoundedCornerShape(20.dp, 20.dp, if (me) 4.dp else 20.dp, if (me) 20.dp else 4.dp),
            colors = CardDefaults.cardColors(containerColor = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(t.text, style = MaterialTheme.typography.bodyLarge)
                t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                if (t.sources.isNotEmpty()) {
                    Text("Sources", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                    t.sources.forEachIndexed { i, src ->
                        TextButton(onClick = { runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url))) } },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                            BtnText("[${i + 1}] ${src.title.take(70)} — ${src.domain}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.time)), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Bumped when the avatar picture, rig or framing changes so views reload. */
object FramingRev { var rev = 0 }

fun avatarFileOf(ctx: android.content.Context): java.io.File? =
    app.belle.assistant.Prefs(ctx).avatarFile.takeIf { it.isNotEmpty() }?.let { java.io.File(ctx.filesDir, it) }

/** Short hint on Home for adding the home-screen widget; hidden once a widget is placed or after "Got it". */
@Composable
private fun WidgetHint(s: UiState, act: MainActivity) {
    val p = s.prefs
    val mgr = android.appwidget.AppWidgetManager.getInstance(act)
    val provider = android.content.ComponentName(act, AssistantWidget::class.java)
    val placed = runCatching { mgr.getAppWidgetIds(provider).isNotEmpty() }.getOrDefault(false)
    if (placed || p.widgetHintDone) return
    Section("Add me to your home screen", WIDGET_HINT) {
        ButtonRow {
            if (android.os.Build.VERSION.SDK_INT >= 26 && mgr.isRequestPinAppWidgetSupported)
                FilledTonalButton(onClick = { mgr.requestPinAppWidget(provider, null, null) }) { BtnText("Add widget") }
            TextButton(onClick = { s.edit { widgetHintDone = true } }) { BtnText("Got it") }
        }
    }
}

const val WIDGET_HINT = "Long-press an empty spot on your home screen › Widgets › Milky Moo's Assistant, then drag it onto the screen " +
    "(or tap Add widget). Resize it for a bigger picture; the switch on it flips 2D/3D."
