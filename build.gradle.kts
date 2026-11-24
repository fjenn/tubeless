// The app is one set of Kotlin sources (common/src) compiled twice: by the Android module
// with Jetpack Compose and by the desktop module with Compose for Desktop. Both expose the
// same androidx.compose.* API, so the screens, the YouTube engine and the library code are
// written once and only the video player differs per platform. The iPad app (:ios, built on a
// Mac) compiles the same sources with Kotlin/Native; Java-only pieces live in common/jvm and
// their iOS twins in common/ios.
// AGP only when :android is part of the build (see settings.gradle.kts); kotlin-android comes with the
// Kotlin plugin below, so both stay in this one class loader as before
buildscript {
    if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()) {
        repositories { if (System.getenv("CI") != null) maven("https://maven-central.storage-download.googleapis.com/maven2/"); google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:8.10.1") }
    }
}
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
