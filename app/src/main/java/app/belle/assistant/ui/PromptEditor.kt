package app.belle.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.belle.assistant.core.ModelCatalog
import app.belle.assistant.core.Tools
import app.belle.assistant.service.AssistantRuntime
import kotlinx.coroutines.delay

private data class SectionInfo(val key: String, val title: String, val hint: String)

private val SECTIONS = listOf(
    SectionInfo("personality", "Personality", "Who they are: character, species, mood, likes, backstory."),
    SectionInfo("style", "How they respond", "Style, length and tone, e.g. \"short and sassy\", \"long, slow and descriptive\"."),
    SectionInfo("address", "How they address you", "Your name, pet names, pronouns, e.g. \"call me Sam or puppy, he/him\"."),
    SectionInfo("extra", "Extra instructions", "Anything else, in your own words. Followed every time."),
)

/**
 * The assistant's system prompt, in four editable sections, per assistant. Changes apply from the very next reply
 * (any model, any chat template; the memory block is separate and unaffected). Every assistant is uncensored for
 * adults; that baseline and the tool instructions are always added and shown in the preview.
 */
@Composable
fun SystemPromptEditor(s: UiState) {
    val p = s.prefs
    var rev by remember(p.id) { mutableIntStateOf(0) }
    var dirty by remember { mutableIntStateOf(0) }
    // Re-fill the model's prompt cache a moment after typing stops, so the next reply starts fast.
    LaunchedEffect(dirty) { if (dirty > 0) { delay(1500); AssistantRuntime.rewarm() } }
    Text("Every assistant is uncensored: ${p.assistantName} talks about anything adult and explicit with you (a consenting adult) without refusing, warning or moralizing. Use the sections below to shape who ${p.assistantName} is.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (sec in SECTIONS) {
        var text by remember(p.id, sec.key, rev) { mutableStateOf(p.promptSection(sec.key)) }
        val edited = remember(p.id, sec.key, rev, text) { p.promptSectionEdited(sec.key) }
        Column(Modifier.padding(top = 8.dp)) {
            OutlinedTextField(text, { v -> text = v; p.setPromptSection(sec.key, v); dirty++ },
                label = { Text(sec.title + if (edited) "" else " (default)") },
                supportingText = { Text(sec.hint) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8)
            if (edited) TextButton(onClick = { p.resetPromptSection(sec.key); rev++; dirty++ }) { BtnText("Reset ${sec.title.lowercase()} to default") }
        }
    }
    var preview by remember { mutableStateOf(false) }
    ButtonRow {
        OutlinedButton(onClick = { p.resetPrompt(); rev++; dirty++ }) { BtnText("Reset all to default") }
        FilledTonalButton(onClick = { preview = !preview }, modifier = Modifier.padding(start = 8.dp)) { BtnText(if (preview) "Hide preview" else "Preview full prompt") }
    }
    if (preview) {
        val rp = ModelCatalog.LLMS.firstOrNull { it.fileName == p.llmFile }?.roleplay == true
        val full = remember(rev, dirty, p.id) { Tools.fullSystemPrompt(p.assistantName, p.persona, rp) }
        Text("This is exactly what the thinking model reads before every message (the conversation memory and your words follow it). ${full.length} characters.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Text(full, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
    }
}

/** Recovery for a conversation that hangs: cancels the reply in progress and starts fresh (memory is kept). */
@Composable
fun ResetConversationButton(act: MainActivity) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("Stuck on \"thinking\" or not answering? This stops the current reply, clears the short-term conversation and starts listening again. Long-term memory is kept.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { AssistantRuntime.unstick(act); act.toast("Conversation reset") }, modifier = Modifier.padding(top = 4.dp)) { BtnText("Reset conversation") }
    }
}
