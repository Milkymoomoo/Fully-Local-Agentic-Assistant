package app.belle.assistant.ui

import app.belle.assistant.core.Platform
import android.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.belle.assistant.core.Memory
import app.belle.assistant.memory.SqlMemoryStore
import java.text.DateFormat
import java.util.Date

val MEMORY_EXPLAINER = "So I don't forget what we talked about, I keep a private memory of our conversations. " +
    "What it stores: what you said and what I answered, a short running summary of older talks, and a list of facts I picked up (like names or things you like). " +
    "Roleplay / story chats get their own separate story memory (characters, setting, plot so far). " +
    "Where: only in this app's private storage on this ${Platform.device}, encrypted with a key kept in the ${Platform.device}'s secure keystore. It is never uploaded, never backed up to the cloud, and other apps can't read it. " +
    "How it works: when our talk gets too long for the thinking model, it writes a summary of the older part itself, on this ${Platform.device}. Each time you speak I give the model the summary, the facts and earlier lines that match what you just said, and our latest exchanges — always trimmed to fit, so it never runs out of room."

/** Short version for the Simple page. */
@Composable
fun MemorySimple(s: UiState, act: MainActivity) {
    val p = s.prefs
    SwitchRow("Remember our conversations", "Kept only on this ${Platform.device}, encrypted, never uploaded. Per assistant.", p.memoryEnabled) { s.edit { memoryEnabled = it } }
        ResetConversationButton(act)
    TextButton(onClick = { s.page = "memory" }) { BtnText("Manage memory ›") }
}

@Composable
fun MemoryPage(s: UiState, act: MainActivity) {
    val p = s.prefs
    var rev by remember { mutableIntStateOf(0) }
    rev
    val store = SqlMemoryStore.of(act, p.id)
    val st = runCatching { store.stats() }.getOrNull()
    Section("Memory for ${p.assistantName}", MEMORY_EXPLAINER) {
        SwitchRow("Remember our conversations", "Off: I only remember the last few lines until I go back to sleep, and nothing new is saved. Saved per assistant.", p.memoryEnabled) { s.edit { memoryEnabled = it } }
        if (st != null) Text("Stored: ${if (st.bytes < 1_000_000) "${(st.bytes + 1023) / 1024} KB" else fmtMb(st.bytes)} · ${st.turns} chat lines · ${st.storyTurns} story lines · ${st.notes} facts · ${st.summaries} summaries" +
            if (store.file.exists()) (if (store.encrypted) " · encrypted" else " · NOT encrypted (keystore unavailable)") else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        Row {
            FilledTonalButton(onClick = {
                AlertDialog.Builder(act).setTitle("Clear ${p.assistantName}'s memory?")
                    .setMessage("Everything ${p.assistantName} remembers (all lines, summaries, facts and story memory) is permanently erased from this ${Platform.device}: the database is wiped and compacted, its file deleted and its encryption key destroyed. This can't be undone.")
                    .setPositiveButton("Clear") { _, _ -> store.clear(); app.belle.assistant.service.AssistantRuntime.resetConversation(); rev++; act.toast("Memory cleared") }
                    .setNegativeButton("Cancel", null).show()
            }) { BtnText("Clear memory") }
            OutlinedButton(onClick = {
                AlertDialog.Builder(act).setTitle("Clear memory for ALL assistants?")
                    .setMessage("Every assistant's memory on this ${Platform.device} is permanently erased (databases wiped, files deleted, keys destroyed). This can't be undone.")
                    .setPositiveButton("Clear all") { _, _ -> SqlMemoryStore.clearAll(act); app.belle.assistant.service.AssistantRuntime.resetConversation(); rev++; act.toast("All memory cleared") }
                    .setNegativeButton("Cancel", null).show()
            }, modifier = Modifier.padding(start = 8.dp)) { BtnText("Clear all assistants") }
        }
    }
    if (st == null || !store.file.exists()) return
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    for (thread in listOf(Memory.CHAT, Memory.STORY)) {
        val title = if (thread == Memory.CHAT) "Conversation memory" else "Story / roleplay memory"
        val sum = store.summary(thread)
        val notes = store.notes(thread)
        val turns = store.turns(thread)
        if (sum == null && notes.isEmpty() && turns.isEmpty()) continue
        Section(title) {
            if (sum != null) Entry("Summary", sum.text, df.format(Date(sum.ts))) { store.deleteSummary(thread); rev++ }
            if (notes.isNotEmpty()) SubHeader("Facts (${notes.size})")
            notes.forEach { n -> Entry(null, n.text, df.format(Date(n.ts))) { store.deleteNote(n.id); rev++ } }
            if (turns.isNotEmpty()) SubHeader("Lines (${turns.size}, newest first)")
            var show by remember(thread) { mutableStateOf(20) }
            turns.asReversed().take(show).forEach { t ->
                Entry(null, "You: ${t.user}\n${p.assistantName}: ${t.reply}", df.format(Date(t.ts)) + if (t.summarized) " · in summary" else "") { store.deleteTurn(t.id); rev++ }
            }
            if (turns.size > show) TextButton(onClick = { show += 50 }) { BtnText("Show more") }
        }
    }
}

@Composable
private fun Entry(title: String?, text: String, meta: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete this entry") }
    }
}
