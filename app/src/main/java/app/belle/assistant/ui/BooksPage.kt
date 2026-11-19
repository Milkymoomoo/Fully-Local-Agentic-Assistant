package app.belle.assistant.ui

import android.app.AlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.belle.assistant.book.Kind
import app.belle.assistant.book.Library
import app.belle.assistant.service.AssistantRuntime

/** "Read to me": the book library, the player, and how it works. Works with every assistant (uses its voice). */
@Composable
fun BooksPage(s: UiState, act: MainActivity) {
    s.tick; s.rev
    val r = AssistantRuntime.reader
    val name = s.prefs.assistantName
    var openChapters by remember { mutableStateOf<String?>(null) }
    var showHow by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AssistantRuntime.importBook(act, uri) { msg -> act.toast(msg); s.rev++ }
    }

    Section("Read to me", "$name reads your EPUB, PDF and text books aloud, word for word, in $name's voice. Everything stays on this phone.") {
        Button(onClick = { pick.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain", "application/octet-stream")) },
            enabled = AssistantRuntime.importing == null, modifier = Modifier.fillMaxWidth()) { BtnText("Add a book") }
        AssistantRuntime.importing?.let { Text(it, modifier = Modifier.padding(top = 8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) }
        TextButton(onClick = { showHow = !showHow }) { BtnText(if (showHow) "Hide how it works" else "How it works ›") }
        if (showHow) HowItWorks(name, s.prefs.wakePhrases.firstOrNull() ?: "hey $name")
    }

    r.book?.let { b ->
        Section("Now ${if (r.playing || r.bargePaused) "reading" else "paused"} · ${b.percent(Library.Position(r.chapter, r.sentence, 0))}%", "${b.title}${if (b.author.isNotBlank()) " · ${b.author}" else ""}") {
            Text(r.chapterTitle, style = MaterialTheme.typography.titleMedium)
            val c = b.chapters.getOrNull(r.chapter)
            if (c != null && c.sentences > 0) LinearProgressIndicator(progress = { r.sentence.toFloat() / c.sentences }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            ButtonRow {
                OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "prev") }) { BtnText("⏮") }
                OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "back") }) { BtnText("↺") }
                Button(onClick = { AssistantRuntime.bookControl(act, "toggle") }) { BtnText(if (r.playing || r.bargePaused) "Pause" else "Play") }
                OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "next") }) { BtnText("⏭") }
                OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "stop") }) { BtnText("■") }
            }
            Text("■ Stop saves your place, ends playback and its notification, and frees the voice. Pause keeps everything ready to continue.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Speed ${"%.2f".format(r.speed)}× (on top of $name's voice speed)", modifier = Modifier.padding(top = 10.dp))
            var sp by remember(b.id) { mutableStateOf(r.speed) }
            Slider(value = sp, onValueChange = { sp = it }, onValueChangeFinished = { r.speed = sp; AssistantRuntime.bookControl(act, "speed", sp.toString()) }, valueRange = 0.6f..2f, steps = 13)
            val left = if (r.sleepAt > 0) ((r.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(0) else 0
            Text(if (r.sleepAt > 0) "Sleep timer: stops in about $left min" else "Sleep timer: off")
            ButtonRow {
                listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 h").forEach { (m, l) -> OutlinedButton(onClick = { AssistantRuntime.bookControl(act, "sleep", "$m") }) { BtnText(l) } }
            }
            ButtonRow {
                TextButton(onClick = { AssistantRuntime.bookControl(act, "unload") }) { BtnText("Unload the book") }
                TextButton(onClick = { AssistantRuntime.bookControl(act, "unloadAll") }) { BtnText("Unload everything") }
            }
        }
    }

    if (r.book == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = { AssistantRuntime.bookControl(act, "unloadAll") }) { BtnText("Unload everything") }
            Text("Frees memory; your books stay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val books = r.lib.list()
    Section("Your books", if (books.isEmpty()) "No books yet. Add one above." else "${books.size} book${if (books.size == 1) "" else "s"} · shared by all your assistants") {
        books.forEach { b ->
            val pos = r.lib.position(b.id)
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(b.author.takeIf { it.isNotBlank() }, b.format.uppercase(), "${b.readable.size} chapters", "${b.words / 1000}k words",
                    pos?.let { if (it.finished) "finished" else "at ${b.chapters.getOrNull(it.chapter)?.title} · ${b.percent(it)}%" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ButtonRow {
                    Button(onClick = { AssistantRuntime.bookControl(act, "play", b.id) }) { BtnText(if (pos != null && !pos.finished) "Resume" else "Play") }
                    OutlinedButton(onClick = { openChapters = if (openChapters == b.id) null else b.id }) { BtnText("Chapters") }
                    TextButton(onClick = {
                        AlertDialog.Builder(act).setTitle("Delete \"${b.title}\"?").setMessage("This removes the book and your place in it from this phone. The original file you picked isn't touched.")
                            .setPositiveButton("Delete") { _, _ -> AssistantRuntime.bookControl(act, "delete", b.id); s.rev++ }.setNegativeButton("Cancel", null).show()
                    }) { BtnText("Delete") }
                }
                if (openChapters == b.id) b.chapters.forEachIndexed { i, c ->
                    Text((if (c.kind == Kind.CHAPTER) "" else "(skipped) ") + c.title + " · ${c.words} words",
                        color = if (c.kind == Kind.CHAPTER) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { r.open(b.id); AssistantRuntime.bookControl(act, "chapter", "$i"); AssistantRuntime.bookControl(act, "play") }.padding(vertical = 6.dp, horizontal = 8.dp))
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun HowItWorks(name: String, wake: String) = Column {
    Help("• Add a book: EPUB, PDF (with a text layer) or plain text. It is cleaned up on the phone: copyright pages, \"sign up for our newsletter\" pages, contents, page numbers and running headers are left out; words split by line-end hyphens are joined again; chapters come from the book's own table of contents (or its chapter headings).")
    Help("• Front matter and back matter (acknowledgments, notes, previews) are kept but not read, unless you pick them in the chapter list.")
    Help("• Reading is word for word with $name's voice (whichever assistant is active), without the thinking model. The next sentences are prepared while one plays, so there are no gaps.")
    Help("• Your place is saved at every sentence, per book. Resume carries on where you stopped.")
    Help("• Say \"$wake\" any time while reading: the book pauses, you can talk to $name, and the book carries on by itself afterwards.")
    Help("• Voice commands (after \"$wake\"): \"read me <book title>\", \"continue reading\", \"pause\", \"stop\", \"resume\", \"next chapter\", \"previous chapter\", \"go to chapter 5\", \"go back\", \"faster\", \"slower\", \"stop reading in 30 minutes\", \"where am I\", and \"what just happened?\" (a short recap of the last few pages by the thinking model; it only sees the last ~1,500 words' worth, never the whole book).")
    Help("• The notification and the home-screen widget show the book and chapter; tap them to play or pause.")
    Help("• Only your own files: books bought with DRM can't be opened. Book text never leaves the phone.")
}
