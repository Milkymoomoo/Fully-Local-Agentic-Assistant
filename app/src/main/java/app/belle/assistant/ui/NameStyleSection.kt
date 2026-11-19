package app.belle.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File

/** Per-profile "name on the widget" settings: style + font, each choice shown as a live preview of the real widget art. */
@Composable
fun NameStyleSection(s: UiState, act: MainActivity) {
    val p = s.prefs
    @Suppress("UNUSED_VARIABLE") val rev = s.rev        // recompose after edits
    val dens = LocalDensity.current.density
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val r = act.contentResolver.openInputStream(uri)?.use { NameArt.importFont(act, act.displayName(uri), it) } ?: Result.failure(IllegalStateException("can't open"))
        r.onSuccess { id -> s.edit { nameFont = id }; AssistantWidget.updateAll(act) }
         .onFailure { act.toast(it.message?.takeIf { m -> m.startsWith("Pick") } ?: "That file isn't a font I can use") }
    }
    val dark = Color(0xFF2A1A2E)   // the widget's own background, so previews look like the widget
    Section("Name on the widget", "Your assistant's full name is always shown in full under the picture: it shrinks to fit or wraps to two lines. Saved for ${p.assistantName} only.") {
        Text("Style", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            NameArt.STYLES.forEach { (id, label) ->
                val on = p.nameStyle == id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .background(dark, RoundedCornerShape(14.dp)).clickable { s.edit { nameStyle = id }; AssistantWidget.updateAll(act) }
                    .padding(10.dp).semantics { contentDescription = "$label${if (on) ", selected" else ""}" }) {
                    NamePreview(act, p.assistantName, id, p.nameFont, 220, 22f, animate = id == "rgb")
                    Text(label + if (on) "  ✓" else "", color = Color(0xFFF1EAF4), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        Text("Font", style = MaterialTheme.typography.titleMedium)
        val fonts = NameArt.FONTS + NameArt.importedFonts(act)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            fonts.forEach { (id, label) ->
                val on = p.nameFont == id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .background(dark, RoundedCornerShape(12.dp)).clickable { s.edit { nameFont = id }; AssistantWidget.updateAll(act) }
                    .padding(8.dp).semantics { contentDescription = "Font $label${if (on) ", selected" else ""}" }) {
                    val bmp = NameArt.render(act, p.assistantName, "plain", id, (130 * dens).toInt(), 18 * dens)
                    Image(bmp.asImageBitmap(), null, modifier = Modifier.width(130.dp).height(44.dp))
                    Text(label, color = Color(0xFFE6DCEA), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        ButtonRow {
            OutlinedButton(onClick = { pickFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")) }) { BtnText("Import font (.ttf / .otf)") }
            if (p.nameFont.startsWith("file:")) TextButton(onClick = {
                val f = File(File(act.filesDir, NameArt.FONT_DIR), p.nameFont.removePrefix("file:"))
                val usedElsewhere = app.belle.assistant.ProfileStore.ids(act).any { it != p.id && app.belle.assistant.ProfileStore.prefsOf(act, it).nameFont == p.nameFont }
                s.edit { nameFont = "sans" }; if (!usedElsewhere) f.delete(); AssistantWidget.updateAll(act)
            }) { BtnText("Remove this font") }
        }
        Help("Widgets can't use your own fonts directly, so the name is drawn as a picture with the font and style you pick here.")
    }
}

/** One live preview of the widget name art; the RGB style animates (only this small composable recomposes). */
@Composable
private fun NamePreview(act: MainActivity, name: String, style: String, font: String, widthDp: Int, sizeDp: Float, animate: Boolean) {
    val dens = LocalDensity.current.density
    var phase by remember { mutableFloatStateOf(0f) }
    if (animate) LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(80); phase = (System.currentTimeMillis() % 3000L) / 3000f } }
    val bmp = NameArt.render(act, name, style, font, (widthDp * dens).toInt(), sizeDp * dens, phase = phase)
    Image(bmp.asImageBitmap(), null, modifier = Modifier.width(widthDp.dp))
}
