package app.belle.assistant

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * One observable version number for every stored setting.
 *
 * Settings live in SharedPreferences, which Compose can't observe. With the Compose compiler's strong skipping
 * (default since Kotlin 2.0) a screen whose arguments didn't change is skipped, and so is every `Section { … }`
 * lambda, so a switch that only wrote its pref never redrew (it looked stuck, and the next unrelated redraw
 * suddenly showed the real value: "the option disappears"). Now every pref read goes through [observe], so the
 * exact composables that show a setting subscribe to it, and every write goes through [save] / [bump], so they redraw.
 */
object SettingsRev {
    val state = mutableIntStateOf(0)
    /** Read in every pref getter: subscribes the calling composable (no-op outside composition). */
    fun observe() { state.intValue }
    /** Also told about every change (the service/widget side); set once by the runtime. */
    @Volatile var onChange: (() -> Unit)? = null
    fun bump() { synchronized(this) { state.intValue = state.intValue + 1 }; onChange?.invoke() }
}

/** apply() + tell the UI. */
fun SharedPreferences.Editor.save() { apply(); SettingsRev.bump() }
