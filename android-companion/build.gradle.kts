plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9.5 provides built-in Kotlin; use the matching Compose compiler plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
