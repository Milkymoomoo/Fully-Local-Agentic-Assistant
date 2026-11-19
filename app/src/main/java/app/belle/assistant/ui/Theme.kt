package app.belle.assistant.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

private fun c(m: Map<String, Long>, k: String) = Color(m.getValue(k))
private val P = app.belle.assistant.core.Palette
private val Light = lightColorScheme(
    primary = c(P.LIGHT, "primary"),
    onPrimary = c(P.LIGHT, "onPrimary"),
    primaryContainer = c(P.LIGHT, "primaryContainer"),
    onPrimaryContainer = c(P.LIGHT, "onPrimaryContainer"),
    secondary = c(P.LIGHT, "secondary"),
    onSecondary = c(P.LIGHT, "onSecondary"),
    secondaryContainer = c(P.LIGHT, "secondaryContainer"),
    onSecondaryContainer = c(P.LIGHT, "onSecondaryContainer"),
    tertiary = c(P.LIGHT, "tertiary"),
    onTertiary = c(P.LIGHT, "onTertiary"),
    tertiaryContainer = c(P.LIGHT, "tertiaryContainer"),
    onTertiaryContainer = c(P.LIGHT, "onTertiaryContainer"),
    error = c(P.LIGHT, "error"),
    onError = c(P.LIGHT, "onError"),
    errorContainer = c(P.LIGHT, "errorContainer"),
    onErrorContainer = c(P.LIGHT, "onErrorContainer"),
    background = c(P.LIGHT, "background"),
    onBackground = c(P.LIGHT, "onBackground"),
    surface = c(P.LIGHT, "surface"),
    onSurface = c(P.LIGHT, "onSurface"),
    surfaceVariant = c(P.LIGHT, "surfaceVariant"),
    onSurfaceVariant = c(P.LIGHT, "onSurfaceVariant"),
    surfaceContainerLowest = c(P.LIGHT, "surfaceContainerLowest"),
    surfaceContainerLow = c(P.LIGHT, "surfaceContainerLow"),
    surfaceContainer = c(P.LIGHT, "surfaceContainer"),
    surfaceContainerHigh = c(P.LIGHT, "surfaceContainerHigh"),
    surfaceContainerHighest = c(P.LIGHT, "surfaceContainerHighest"),
    inverseSurface = c(P.LIGHT, "inverseSurface"),
    inverseOnSurface = c(P.LIGHT, "inverseOnSurface"),
    inversePrimary = c(P.LIGHT, "inversePrimary"),
    outline = c(P.LIGHT, "outline"),
    outlineVariant = c(P.LIGHT, "outlineVariant"),
)
private val Dark = darkColorScheme(
    primary = c(P.DARK, "primary"),
    onPrimary = c(P.DARK, "onPrimary"),
    primaryContainer = c(P.DARK, "primaryContainer"),
    onPrimaryContainer = c(P.DARK, "onPrimaryContainer"),
    secondary = c(P.DARK, "secondary"),
    onSecondary = c(P.DARK, "onSecondary"),
    secondaryContainer = c(P.DARK, "secondaryContainer"),
    onSecondaryContainer = c(P.DARK, "onSecondaryContainer"),
    tertiary = c(P.DARK, "tertiary"),
    onTertiary = c(P.DARK, "onTertiary"),
    tertiaryContainer = c(P.DARK, "tertiaryContainer"),
    onTertiaryContainer = c(P.DARK, "onTertiaryContainer"),
    error = c(P.DARK, "error"),
    onError = c(P.DARK, "onError"),
    errorContainer = c(P.DARK, "errorContainer"),
    onErrorContainer = c(P.DARK, "onErrorContainer"),
    background = c(P.DARK, "background"),
    onBackground = c(P.DARK, "onBackground"),
    surface = c(P.DARK, "surface"),
    onSurface = c(P.DARK, "onSurface"),
    surfaceVariant = c(P.DARK, "surfaceVariant"),
    onSurfaceVariant = c(P.DARK, "onSurfaceVariant"),
    surfaceContainerLowest = c(P.DARK, "surfaceContainerLowest"),
    surfaceContainerLow = c(P.DARK, "surfaceContainerLow"),
    surfaceContainer = c(P.DARK, "surfaceContainer"),
    surfaceContainerHigh = c(P.DARK, "surfaceContainerHigh"),
    surfaceContainerHighest = c(P.DARK, "surfaceContainerHighest"),
    inverseSurface = c(P.DARK, "inverseSurface"),
    inverseOnSurface = c(P.DARK, "inverseOnSurface"),
    inversePrimary = c(P.DARK, "inversePrimary"),
    outline = c(P.DARK, "outline"),
    outlineVariant = c(P.DARK, "outlineVariant"),
)

private fun TextStyle.scaled(f: Float) = copy(
    fontSize = fontSize * f,
    lineHeight = if (lineHeight == TextUnit.Unspecified) 1.35.em else lineHeight * f,
)

/** Material 3 theme. Dark by default (WCAG AA-checked palette); light / follow-system options; optional wallpaper colours on Android 12+. */
@Composable
fun BelleTheme(mode: String, large: Boolean, wallpaper: Boolean = false, content: @Composable () -> Unit) {
    // Dark is the default; "system" follows the phone; "light" is light.
    val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val ctx = LocalContext.current
    val colors = when {
        wallpaper && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    // Slightly larger than stock everywhere for readability; "Large text" adds 15% more.
    val f = if (large) 1.2f else 1.05f
    val t = Typography()
    val type = Typography(
        displaySmall = t.displaySmall.scaled(f), headlineMedium = t.headlineMedium.scaled(f), headlineSmall = t.headlineSmall.scaled(f),
        titleLarge = t.titleLarge.scaled(f), titleMedium = t.titleMedium.scaled(f), titleSmall = t.titleSmall.scaled(f),
        bodyLarge = t.bodyLarge.scaled(f), bodyMedium = t.bodyMedium.scaled(f), bodySmall = t.bodySmall.scaled(f),
        labelLarge = t.labelLarge.scaled(f), labelMedium = t.labelMedium.scaled(f), labelSmall = t.labelSmall.scaled(f),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
