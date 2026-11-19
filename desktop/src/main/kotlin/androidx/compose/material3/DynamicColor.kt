package androidx.compose.material3

/** Android 12 wallpaper colours don't exist on a PC: the app's own palettes are used (the switch is hidden there). */
fun dynamicDarkColorScheme(ctx: android.content.Context): ColorScheme = darkColorScheme()
fun dynamicLightColorScheme(ctx: android.content.Context): ColorScheme = lightColorScheme()
