// CI: Google's mirror of Maven Central first; GitHub's runners share addresses that Maven Central
// throttles (HTTP 429, seen on macOS runners 2026-11-24), and a 429 fails the build instead of falling through
val ciMirror = System.getenv("CI") != null
pluginManagement {
    repositories {
        if (System.getenv("CI") != null) maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_SETTINGS: Kotlin/Wasm's Gradle plugin adds its own tool repositories (binaryen); they are declared here
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (ciMirror) maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        // binaryen, the wasm optimizer of :web's release build
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "tubeless-app"
include(":desktop")
// Android only where its SDK is set up (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties): CI, or a dev
// machine that has it. Elsewhere the build skips it and never downloads the Android Gradle plugin.
val androidOn = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()
if (androidOn) include(":android")
// the browser extension: common/src compiled to WebAssembly (wasmJs)
include(":web")
// the iPad app needs Xcode, so it is only part of the build on a Mac
if (System.getProperty("os.name").startsWith("Mac") && file("ios/build.gradle.kts").exists()) {
    include(":ios")
}
