package app.belle.assistant.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * App colours (ARGB). Every "on" colour is checked against its background for
 * WCAG AA (>= 4.5:1 for text) in [PaletteTest].
 */
object Palette {
    val DARK = mapOf(
        "primary" to 0xFFD2BCFF, "onPrimary" to 0xFF3B1E70,
        "primaryContainer" to 0xFF523788, "onPrimaryContainer" to 0xFFEADDFF,
        "secondary" to 0xFFFFB597, "onSecondary" to 0xFF4F2513,
        "secondaryContainer" to 0xFF6D4330, "onSecondaryContainer" to 0xFFFFDBCB,
        "tertiary" to 0xFF98D5C5, "onTertiary" to 0xFF003730,
        "tertiaryContainer" to 0xFF125246, "onTertiaryContainer" to 0xFFB4F1E1,
        "error" to 0xFFFFB4AB, "onError" to 0xFF690005,
        "errorContainer" to 0xFF93000A, "onErrorContainer" to 0xFFFFDAD6,
        "background" to 0xFF151218, "onBackground" to 0xFFE8E0E8,
        "surface" to 0xFF151218, "onSurface" to 0xFFE8E0E8,
        "surfaceVariant" to 0xFF4A4454, "onSurfaceVariant" to 0xFFCDC3D6,
        "surfaceContainerLowest" to 0xFF100D12, "surfaceContainerLow" to 0xFF1D1A20,
        "surfaceContainer" to 0xFF221E25, "surfaceContainerHigh" to 0xFF2C2830, "surfaceContainerHighest" to 0xFF37323B,
        "inverseSurface" to 0xFFE8E0E8, "inverseOnSurface" to 0xFF322F35, "inversePrimary" to 0xFF6B4EA2,
        "outline" to 0xFF978E9F, "outlineVariant" to 0xFF4A4454,
    )
    val LIGHT = mapOf(
        "primary" to 0xFF6B4EA2, "onPrimary" to 0xFFFFFFFF,
        "primaryContainer" to 0xFFEADDFF, "onPrimaryContainer" to 0xFF25005A,
        "secondary" to 0xFF7A4B36, "onSecondary" to 0xFFFFFFFF,
        "secondaryContainer" to 0xFFFFDBCB, "onSecondaryContainer" to 0xFF331100,
        "tertiary" to 0xFF2E6A5E, "onTertiary" to 0xFFFFFFFF,
        "tertiaryContainer" to 0xFFB4F1E1, "onTertiaryContainer" to 0xFF00201B,
        "error" to 0xFFBA1A1A, "onError" to 0xFFFFFFFF,
        "errorContainer" to 0xFFFFDAD6, "onErrorContainer" to 0xFF410002,
        "background" to 0xFFFFFBFF, "onBackground" to 0xFF1D1B20,
        "surface" to 0xFFFFFBFF, "onSurface" to 0xFF1D1B20,
        "surfaceVariant" to 0xFFE7E0EB, "onSurfaceVariant" to 0xFF49454E,
        "surfaceContainerLowest" to 0xFFFFFFFF, "surfaceContainerLow" to 0xFFF8F2F8,
        "surfaceContainer" to 0xFFF4EFF4, "surfaceContainerHigh" to 0xFFEEE8EF, "surfaceContainerHighest" to 0xFFE8E2E9,
        "inverseSurface" to 0xFF322F35, "inverseOnSurface" to 0xFFF5EFF7, "inversePrimary" to 0xFFD2BCFF,
        "outline" to 0xFF7A757F, "outlineVariant" to 0xFFCAC4CF,
    )

    /** Text pairs that must reach 4.5:1 (foreground, background). */
    val TEXT_PAIRS = listOf(
        "onPrimary" to "primary", "onPrimaryContainer" to "primaryContainer",
        "onSecondary" to "secondary", "onSecondaryContainer" to "secondaryContainer",
        "onTertiary" to "tertiary", "onTertiaryContainer" to "tertiaryContainer",
        "onError" to "error", "onErrorContainer" to "errorContainer",
        "onBackground" to "background", "onSurface" to "surface", "onSurfaceVariant" to "surface",
        "onSurface" to "surfaceContainer", "onSurfaceVariant" to "surfaceContainer",
        "onSurface" to "surfaceContainerHigh", "onSurfaceVariant" to "surfaceContainerHigh",
        "onSurface" to "surfaceContainerHighest", "onSurfaceVariant" to "surfaceContainerHighest",
        "onSurfaceVariant" to "surfaceVariant", "inverseOnSurface" to "inverseSurface",
        "primary" to "surface", "primary" to "surfaceContainer", "primary" to "surfaceContainerHigh",  // text buttons / links
        "error" to "surface", "error" to "surfaceContainer", "tertiary" to "surfaceContainer", "secondary" to "surfaceContainer",
    )

    private fun lum(c: Long): Double {
        fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    fun contrast(a: Long, b: Long): Double { val x = lum(a); val y = lum(b); return (max(x, y) + 0.05) / (min(x, y) + 0.05) }
}
