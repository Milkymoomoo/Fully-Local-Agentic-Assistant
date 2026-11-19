package app.belle.assistant.ui

import app.belle.assistant.core.Platform
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.belle.assistant.core.ModelCatalog
import app.belle.assistant.models.Dl
import app.belle.assistant.models.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One model row's facts that need the disk or a system call, worked out once off the main thread. */
data class ModelRowInfo(val d: Dl, val have: Boolean, val fit: String, val level: Int, val recommended: Boolean)
data class ModelGroup(val title: String, val help: String, val rows: List<ModelRowInfo>)

object ModelsPageData {
    fun voiceOf(d: Dl) = if (d.kind != "voice") null else ModelCatalog.VOICES.firstOrNull { "voice:" + it.dirName == d.id }

    /**
     * The page's groups with each row's facts. [installed] is the only disk access; it, the RAM size and the
     * recommended voice are looked up once per load here, never per frame. Call it off the main thread.
     */
    fun build(cat: List<Dl>, ramGb: Double, recommendedVoiceId: String?, installed: (Dl) -> Boolean): List<ModelGroup> {
        fun info(d: Dl) = Downloads.ramFit(ramGb, d).let { (fit, level) -> ModelRowInfo(d, installed(d), fit, level, voiceOf(d)?.id != null && voiceOf(d)?.id == recommendedVoiceId) }
        fun llm(id: String) = llmOf(id)
        return listOf(
            Triple("Models › Speech recognition (required)", "Needed so I understand you. Works offline after download.", cat.filter { it.kind == "stt" }),
            Triple("Models › Thinking model — Uncensored (trained, recommended)", "Dolphin models by Cognitive Computations, fine-tuned on uncensored data: they answer plainly and don't refuse or lecture. Bigger = smarter but slower and uses more memory.", cat.filter { it.kind == "llm" && llm(it.id)?.let { m -> m.censor == "uncensored" && !m.roleplay } == true }),
            Triple("Models › Thinking model — Abliterated", "Standard models with the refusal behaviour removed afterwards by the community. Fewer refusals than Standard, but unlike trained-uncensored models they can still refuse sometimes.", cat.filter { it.kind == "llm" && llm(it.id)?.censor == "abliterated" }),
            Triple("Models › Thinking model — Standard", "As released by Qwen / Meta. More cautious.", cat.filter { it.kind == "llm" && llm(it.id)?.censor.let { c -> c == null || c == "standard" } }),
            Triple("Models › Roleplay / story", "Creative-writing and roleplay fine-tunes (uncensored, trained). Choosing one switches me to a story / roleplay persona.", cat.filter { it.kind == "llm" && llm(it.id)?.roleplay == true }),
            Triple("Voice › High-quality voices", "Recommended. Piper \"high\" models: clearer and more natural, a bit slower. ★ = best fit for this ${Platform.device}'s memory (used automatically once downloaded).", cat.filter { d -> voiceOf(d)?.let { it.engine == "piper" && it.quality == "high" } == true }.sortedBy { d -> if (voiceOf(d)?.id == recommendedVoiceId) 0 else 1 }),
            Triple("Voice › Standard voices (int8)", "Small and fast. ▶ plays the official sample.", cat.filter { d -> voiceOf(d)?.let { it.engine == "piper" && it.precision == "int8" && it.quality != "high" } == true }),
            Triple("Voice › Full-precision voices", "Same voices without int8 rounding: smoothest Piper sound, 3–4× bigger.", cat.filter { d -> voiceOf(d)?.let { it.engine == "piper" && it.precision == "fp32" && it.quality != "high" } == true }),
            Triple("Voice › Kokoro (most natural)", "Very natural, but slower to start each sentence. Best on 8 GB+ phones.", cat.filter { d -> voiceOf(d)?.engine == "kokoro" }),
        ).filter { it.third.isNotEmpty() }.map { (t, h, l) -> ModelGroup(t, h, l.map(::info)) }
    }

    /** Which downloads changed state in a way that can change "installed" (not every progress tick). */
    fun installKey(): String = Downloads.state.entries.filter { it.value.phase != Downloads.Phase.DOWNLOADING }
        .sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.phase}" }
}

private val BUSY = setOf(Downloads.Phase.QUEUED, Downloads.Phase.DOWNLOADING, Downloads.Phase.VERIFYING, Downloads.Phase.INSTALLING)
private fun fmtBytes(b: Long) = if (b >= 1_000_000_000) "%.2f GB".format(b / 1e9) else "${b / 1_000_000} MB"

/**
 * Settings › Downloads (one tap). A lazy list: only the rows on screen are built, and everything that touches the disk
 * or the system (installed?, RAM, recommended voice) is loaded on a background thread. Download progress only
 * recomposes the visible rows.
 */
@Composable
fun GetModelsPage(s: UiState, act: MainActivity, modifier: Modifier = Modifier) {
    val D = Downloads
    val tick = remember { mutableIntStateOf(0) }
    var installKey by remember { mutableStateOf(ModelsPageData.installKey()) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick.intValue++; ModelsPageData.installKey().let { if (it != installKey) installKey = it } }
        D.addListener(l); onDispose { D.removeListener(l) }
    }
    var ram by remember { mutableStateOf<Double?>(null) }
    var groups by remember { mutableStateOf<List<ModelGroup>?>(null) }
    LaunchedEffect(installKey, s.rev) {
        val (r, g) = withContext(Dispatchers.IO) {
            val r = D.totalRamGb(act)
            r to ModelsPageData.build(D.catalog(), r, ModelCatalog.recommendedVoice(ramGb(act))?.id) { D.installed(act, it) }
        }
        ram = r; groups = g
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) act.importFile(uri) }
    var ask by remember { mutableStateOf<Dl?>(null) }
    ask?.let { d ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { ask = null },
            title = { Text("Download ${d.label}?") },
            text = { Text("This connects to ${if (d.url.contains("github") || d.parts.any { it.url.contains("github") }) "GitHub" else "Hugging Face"} only to download ${fmtBytes(d.bytes)}. " +
                "Nothing about you is sent, and the file is checked (SHA-256) before use. Internet access for answers stays ${if (s.prefs.internetEnabled) "on" else "off"}." +
                "\n\nTip: use Wi-Fi for big files.") },
            confirmButton = { Button(onClick = { D.start(act, d); ask = null }) { BtnText("Download") } },
            dismissButton = { TextButton(onClick = { ask = null }) { BtnText("Cancel") } })
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("models_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "intro") {
            Section("${Platform.oneTapCap} downloads", "Hugging Face and GitHub files (official uploads, plus the clearly labelled less-restricted models). Downloads resume if interrupted and are checked before use." +
                (ram?.let { " Ram: your ${Platform.device} has ${"%.1f".format(it)} GB." } ?: "")) {
                ImportFileRow { picker.launch(arrayOf("*/*")) }
            }
        }
        val g = groups
        if (g == null) item(key = "loading") {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Checking what's installed…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        } else g.forEach { grp ->
            item(key = "h:" + grp.title) { GroupTop(grp.title, grp.help) }
            items(grp.rows, key = { "r:" + it.d.id }) { row -> CardPart { ModelRow(row, tick, s, act) { ask = it } } }
            item(key = "b:" + grp.title) { GroupBottom() }
        }
        item(key = "3d") {
            // Photo → 3D has a single install place (the 3D avatar page), so it isn't listed twice.
            Section("Photo → 3D models (optional)", "Making a 3D avatar from a photo needs a separate 5.3 GB download. It is installed from the 3D avatar page.") {
                NavRow("🧊", "3D avatar", "Step 2: Install 3D generation · Step 3: Make 3D from a photo") { s.page = "avatar3d" }
            }
        }
    }
}

/** Downloads' import button: a short one-line label with the explanation under it (the old long label was cut off with large text). */
@Composable
internal fun ImportFileRow(onImport: () -> Unit) {
    OutlinedButton(onClick = onImport, modifier = Modifier.testTag("import_file")) { BtnText("Import a file") }
    Help("Already have a model file on this ${Platform.device}? Import it instead of downloading.")
}

// a Section card cut into lazy items: rounded top with the title, plain middle rows, rounded bottom
@Composable private fun GroupTop(title: String, help: String) =
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
@Composable private fun CardPart(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp)) { content() }
@Composable private fun GroupBottom() =
    Spacer(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(16.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)))

@Composable
private fun ModelRow(row: ModelRowInfo, tick: State<Int>, s: UiState, act: MainActivity, onAsk: (Dl) -> Unit) {
    tick.value   // download progress recomposes only the rows on screen
    val d = row.d; val have = row.have
    val st = Downloads.state[d.id] ?: Downloads.St()
    val busy = st.phase in BUSY
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // info first, then the progress, then the action button under it (full width while downloading):
        // a button never sits beside text or a bar, so nothing is squeezed at large text sizes
        Text(d.label, style = MaterialTheme.typography.titleMedium)
        if (row.recommended) Text("★ Recommended for this ${Platform.device}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        llmOf(d.id)?.let { m -> Text(m.censorLabel + if (m.roleplay) " · Roleplay / story" else "", style = MaterialTheme.typography.labelMedium,
            color = when (m.censor) { "uncensored" -> MaterialTheme.colorScheme.primary; "abliterated" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }) }
        Text(listOfNotNull(fmtBytes(d.bytes), row.fit.takeIf { d.minRamGb > 0 }, d.license.takeIf { it.isNotEmpty() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = when (row.level) { 2 -> MaterialTheme.colorScheme.error; 1 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        if (d.note.isNotEmpty()) Text(d.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val v = ModelsPageData.voiceOf(d)
        if (v != null) Requires(*voiceReqs(v, have).toTypedArray())
        else Requires(if (have) "Installed" else "📦 ${fmtMb(d.bytes)} download", if (d.minRamGb > 0) "🧠 needs ${d.minRamGb} GB RAM" else null, "✈️ works offline" + if (have) "" else " after download")
        if (v != null) TextButton(onClick = { Thread { app.belle.assistant.speech.VoicePreview.play(act, v, have, s.prefs.previewPhrase) { m -> act.runOnUiThread { act.toast(m) } } }.start() }) { BtnText("▶ Play sample") }
        if (have && !busy) Text("Installed ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (st.phase != Downloads.Phase.IDLE && !(have && st.phase == Downloads.Phase.DONE)) {
            val frac = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            val eta = if (st.phase == Downloads.Phase.DOWNLOADING || st.phase == Downloads.Phase.QUEUED) queueEta(act)[d.id] else null
            Text("${(frac * 100).toInt()}% · ${fmtBytes(st.done)} of ${fmtBytes(st.total)}" + (eta?.let { " · $it" } ?: "") + (if (st.msg.isNotEmpty()) " · ${st.msg}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (st.phase == Downloads.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            busy -> OutlinedButton(onClick = { Downloads.cancel(d.id) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { BtnText("Pause") }
            !have -> Button(onClick = { onAsk(d) }, modifier = Modifier.padding(top = 6.dp)) { BtnText(if (st.phase == Downloads.Phase.PAUSED || st.done > 0) "Resume" else "Download") }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** Time left per download: everything ahead of it in the queue ÷ the smoothed speed (pure arithmetic, no I/O). */
private fun queueEta(act: MainActivity): Map<String, String> {
    val dl = Downloads.Phase.DOWNLOADING; val q = Downloads.Phase.QUEUED
    val line = Downloads.state.entries.filter { it.value.phase == dl || it.value.phase == q }.sortedBy { if (it.value.phase == dl) 0 else 1 }
    val e = Downloads.eta(act).queue(line.map { (it.value.total - it.value.done).coerceAtLeast(0) })
    return line.mapIndexed { i, en -> en.key to app.belle.assistant.core.Eta.format(e[i]) }.toMap()
}
