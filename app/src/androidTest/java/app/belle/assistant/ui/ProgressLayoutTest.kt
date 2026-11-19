package app.belle.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-phone bug (0.1.3, OUKITEL WP100 TITAN): when a photo → 3D build started, "Stop" sat beside the Make button and was
 * squeezed until it wrapped one letter per line (S/T/O/P). These tests lay the progress block and button rows out at
 * narrow widths and large font scales (1.0, 1.3, 2.0) and check: every button label is ONE line, Stop is full width
 * and BELOW the bar. Screenshots go to app/build/outputs/connected_android_test_additional_output/.
 */
@RunWith(AndroidJUnit4::class)
class ProgressLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val scales = listOf(1.0f, 1.3f, 2.0f)
    private val widths = listOf(280.dp, 360.dp, 411.dp)

    @Composable private fun At(width: Dp, fontScale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            MaterialTheme { Surface { Box(Modifier.width(width).testTag("frame")) { content() } } }
        }
    }

    private fun lines(text: String): Int {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()?.lineCount ?: error("no text layout for '$text'")
    }

    private fun shot(name: String) = runCatching {
        // AGP pulls this folder into app/build/outputs/connected_android_test_additional_output/
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-test").path
        val dir = File(out).apply { mkdirs() }
        val bmp = rule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every width × font scale, re-laid out in ONE composition (a test may set content only once). */
    private fun sweep(content: @Composable () -> Unit, check: (Dp, Float) -> Unit) {
        var cur by mutableStateOf(widths[0] to scales[0])
        rule.setContent { At(cur.first, cur.second, content) }
        for (w in widths) for (f in scales) { rule.runOnIdle { cur = w to f }; rule.waitForIdle(); check(w, f) }
    }

    @Test fun progressBlockStopIsFullWidthBelowTheBarAndOneLine() {
        sweep({ Column { ProgressBlock("Making a 3D model for Milly", "Detailed shape 7/12", 0.62f, "about 9 min left",
            stopLabel = "Stop", onStop = {}, scrollKey = null) } }) { w, f ->
            val tag = "w${w.value.toInt()}_x$f"
            assertEquals("Stop wraps at $tag", 1, lines("Stop"))
            val stop = rule.onNodeWithTag("progress_stop").getUnclippedBoundsInRoot()
            val stage = rule.onNodeWithTag("progress_stage").getUnclippedBoundsInRoot()
            val frame = rule.onNodeWithTag("frame").getUnclippedBoundsInRoot()   // = w, or the screen width if narrower
            assertTrue("Stop not full width at $tag", stop.right - stop.left >= frame.right - frame.left - 1.dp)
            assertTrue("Stop not below the status at $tag", stop.top >= stage.bottom)
            rule.onNodeWithText("Step 7 of 12", substring = true).assertExists()
            shot("progress_$tag")
        }
    }

    @Test fun buttonRowsNeverSqueezeALabel() {
        sweep({ ButtonRow {
            Button(onClick = {}) { BtnText("Make 3D from a photo") }
            OutlinedButton(onClick = {}) { BtnText("Stop") }
            OutlinedButton(onClick = {}) { BtnText("Cancel (keeps what has arrived)") }
        } }) { w, f ->
            for (t in listOf("Make 3D from a photo", "Stop", "Cancel (keeps what has arrived)")) assertEquals("'$t' wraps at w=$w x$f", 1, lines(t))
            val stop = rule.onNodeWithText("Stop", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Stop squeezed at w=$w x$f", stop.right - stop.left >= stop.bottom - stop.top)   // wider than tall
            shot("buttonrow_w${w.value.toInt()}_x$f")
        }
    }

    @Test fun choiceRowsStayOnScreenWithLargeText() {
        val opts = listOf(1 to "Small", 2 to "Medium", 3 to "Large", 4 to "Very large")
        sweep({ ChoiceRow("Bubble size", null, opts, 2) {} }) { w, f ->
            val root = rule.onRoot().getUnclippedBoundsInRoot()
            for ((_, t) in opts) {
                assertEquals("'$t' wraps at w=$w x$f", 1, lines(t))
                val b = rule.onNodeWithText(t, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("'$t' off screen at w=$w x$f", b.right <= root.right + 1.dp)
            }
            shot("choicerow_w${w.value.toInt()}_x$f")
        }
    }

    /** Downloads (0.1.4 report): "Or import a file you already have" was cut off with large text. */
    @Test fun downloadsImportButtonIsOneLineAndOnScreen() {
        sweep({ Section("One-tap downloads", "Hugging Face and GitHub files. Ram: your phone has 11.2 GB.") { ImportFileRow {} } }) { w, f ->
            assertEquals("'Import a file' wraps at w=$w x$f", 1, lines("Import a file"))
            val root = rule.onRoot().getUnclippedBoundsInRoot()
            val b = rule.onNodeWithTag("import_file").getUnclippedBoundsInRoot()
            assertTrue("import button off screen at w=$w x$f", b.right <= root.right + 1.dp)
            rule.onNodeWithText("Already have a model file", substring = true).assertExists()
            shot("downloads_import_w${w.value.toInt()}_x$f")
        }
    }

    /** Control: the old layout (Make + Stop in a plain Row) really does break Stop at 2.0, so the checks above can fail. */
    @Test fun oldLayoutReproducesTheBug() {
        rule.setContent {
            At(360.dp, 2.0f) { Row(Modifier.fillMaxWidth()) {
                Button(onClick = {}) { Text("Make 3D from a photo") }
                OutlinedButton(onClick = {}) { Text("Stop") }
            } }
        }
        rule.waitForIdle()
        shot("old_row_w360_x2.0")
        assertTrue("expected the old layout to wrap Stop", lines("Stop") > 1)
    }
}
