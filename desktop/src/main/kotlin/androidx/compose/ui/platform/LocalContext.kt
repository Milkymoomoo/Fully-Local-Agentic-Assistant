package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Desktop stand-in for Compose-Android's LocalContext: the one desktop context (the main window's activity when inside it). */
val LocalContext = staticCompositionLocalOf<android.content.Context> { app.belle.desktop.DesktopContext }
