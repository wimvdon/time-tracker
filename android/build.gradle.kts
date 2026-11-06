buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself (built-in Kotlin) and ships with an older Kotlin Gradle
        // plugin. Pin it to the same version as the Compose and serialization compiler plugins.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
}
