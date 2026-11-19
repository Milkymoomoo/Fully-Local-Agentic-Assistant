package app.belle.assistant.ui

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** A titled card that groups related settings. */
@Composable
fun Section(title: String, help: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Help(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 4.dp))

@Composable
fun SwitchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(title: String, valueLabel: String, help: String?, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v, onValueChange = { v = it; onChange(it) }, valueRange = range, steps = steps)
    }
}

@Composable
fun <T> ChoiceRow(title: String, help: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // with large text (or many long labels) the segments can't fit one line: a list of radio rows instead
        val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f || options.sumOf { it.second.length } > 32
        if (large) Column(Modifier.selectableGroup()) {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().selectable(value == selected, role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(value) }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = value == selected, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(selected = value == selected, onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { BtnText(label) }
            }
        }
    }
}

/** A tappable row that opens a sub-screen. */
@Composable
fun NavRow(icon: String, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Editable list of phrases (wake words, sleep words): chips with ✕ to remove,
 * a text box + Add button, and optional one-tap suggestions. No limit on count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhraseList(phrases: List<String>, placeholder: String, suggestions: List<String>, onChange: (List<String>) -> Unit, validate: (String) -> String? = { null }) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun add(p: String) {
        val t = p.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return
        if (phrases.any { it.equals(t, true) }) { error = "\"$t\" is already in the list"; return }
        val e = validate(t); if (e != null) { error = e; return }
        error = null; onChange(phrases + t); input = ""
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        phrases.forEach { p ->
            InputChip(selected = true, onClick = {}, label = { Text(p, style = MaterialTheme.typography.bodyLarge) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $p", Modifier.width(InputChipDefaults.AvatarSize).clickable {
                        onChange(phrases - p)
                    })
                })
        }
    }
    if (phrases.isEmpty()) Help("None yet — add one below.")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(value = input, onValueChange = { input = it; error = null }, label = { Text(placeholder) },
            singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add(input) }),
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = { add(input) }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
    }
    val left = suggestions.filter { s -> phrases.none { it.equals(s, true) } }
    if (left.isNotEmpty()) {
        Text("Suggestions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            left.forEach { s -> AssistChip(onClick = { add(s) }, label = { Text("+ $s") }) }
        }
    }
}

@Composable
fun IconTitle(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text)
}

/** A collapsed "▸ Title" row that opens to show [content]; for advanced or rarely needed options. */
@Composable
fun Expander(title: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text((if (open) "▾ " else "▸ ") + title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp))
        if (open) content()
    }
}
