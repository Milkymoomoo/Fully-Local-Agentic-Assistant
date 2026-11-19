package app.belle.assistant.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.belle.assistant.core.ProgressText

/*
 * Layout rules for every button and every progress block in the app (see AGENTS.md › Rules):
 *  - a button label is ONE line: never wrapped (and never one letter per line); if it can't fit it ends in "…"
 *    (BtnText), so keep labels short and put explanations in text under the button;
 *  - buttons side by side go in a ButtonRow (a FlowRow): when they don't fit they move to the next line instead of
 *    being squeezed;
 *  - a long job shows a ProgressBlock: stage, step, time left and the bar, with a full-width Stop BELOW it, never beside
 *    a weighted bar. When the block appears it scrolls itself to the top of the page.
 */

/** One-line button label. Use instead of Text inside Button/OutlinedButton/TextButton. */
@Composable
fun BtnText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified, textAlign: TextAlign? = null) {
    val c = color.takeOrElse(LocalContentColor.current)
    // one cheap layout per label (no auto-size passes: with 100+ buttons that cost real time on slow phones). Button
    // labels grow with the system font size up to 1.5×; beyond that they stay at 1.5× so a label still fits a line
    // (body text keeps growing)
    val fs = LocalDensity.current.fontScale
    val size = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 14.sp
    val capped = if (fs > MAX_LABEL_SCALE) style.merge(TextStyle(fontSize = (size.value * MAX_LABEL_SCALE / fs).sp)) else style
    BasicText(text, modifier.widthIn(min = 24.dp), style = capped.merge(TextStyle(color = c, textAlign = textAlign ?: TextAlign.Unspecified)),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}
/** Button labels follow the system font size up to this factor. */
const val MAX_LABEL_SCALE = 1.5f
private fun Color.takeOrElse(o: Color) = if (this == Color.Unspecified) o else this

/** Buttons side by side that wrap onto the next line instead of squeezing each other. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { content() }

/** The page's scroll state and its scrolled content, so a block can bring itself to the top (provided by SettingsScreen). */
class PageScroll(val state: ScrollState) { var content: LayoutCoordinates? = null }
val LocalPageScroll = compositionLocalOf<PageScroll?> { null }

/** Scrolls the page so this element sits at the top, once, when it first appears (e.g. when a build starts). */
@Composable
fun Modifier.scrollToTopWhenShown(key: Any = Unit): Modifier {
    val page = LocalPageScroll.current ?: return this
    val holder = remember(key) { arrayOfNulls<LayoutCoordinates>(1) }
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(key) {
        repeat(10) { if (holder[0] == null || page.content == null) withFrameNanos { } }
        val me = holder[0] ?: return@LaunchedEffect; val content = page.content ?: return@LaunchedEffect
        if (!me.isAttached || !content.isAttached) return@LaunchedEffect
        val y = content.localPositionOf(me, Offset.Zero).y - margin
        page.state.animateScrollTo(y.toInt().coerceIn(0, page.state.maxValue))
    }
    return this.onGloballyPositioned { holder[0] = it }
}

/**
 * The status block of a long job: title, stage, step, time left, bar and percent, and a full-width [stopLabel] button
 * under it. [label] is the job's progress text ("Detailed shape 7/12"), split by [ProgressText].
 */
@Composable
fun ProgressBlock(title: String, label: String?, fraction: Float?, eta: String?, stopLabel: String?, onStop: (() -> Unit)?,
                  scrollKey: Any? = title, modifier: Modifier = Modifier, stopHint: String? = null, stepWord: String = "Step") {
    val parts = ProgressText.split(label)
    Column((if (scrollKey != null) modifier.scrollToTopWhenShown(scrollKey) else modifier).fillMaxWidth().padding(vertical = 6.dp).testTag("progress_block")) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(parts.stage, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("progress_stage"))
        val sub = listOfNotNull(parts.stepLine(stepWord), fraction?.let { ProgressText.percent(it) }, eta ?: "estimating time left…").joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (stopLabel != null && onStop != null)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("progress_stop")) { BtnText(stopLabel) }
        // what the button keeps or loses goes under it, so the label itself stays short enough for one line
        if (stopHint != null && onStop != null) Text(stopHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A text field with its button: side by side normally; with large text the button goes under the full-width field. */
@Composable
fun FieldRow(field: @Composable (Modifier) -> Unit, button: @Composable () -> Unit) {
    if (LocalDensity.current.fontScale > 1.15f) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { field(Modifier.fillMaxWidth()); button() }
    else androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field(Modifier.weight(1f)); button()
    }
}
