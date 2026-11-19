plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Windows/Linux desktop port (module :desktop): Kotlin/JVM + Compose Multiplatform Desktop
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
