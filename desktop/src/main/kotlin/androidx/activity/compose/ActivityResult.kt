package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Desktop stand-in: the contract runs a desktop dialog (file chooser) and hands its result back on the UI thread. */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> {
    val cb = rememberUpdatedState(onResult)
    return remember(contract) { ActivityResultLauncher { input -> contract.run(input) { out -> app.belle.desktop.ui.UiThread.post { cb.value(out) } } } }
}

/** The back gesture: the desktop window has its own Back button and Esc handling. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}
