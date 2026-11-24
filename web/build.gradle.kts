// The browser extension: the shared sources (common/src) plus their browser pieces (common/web)
// and the extension's page (src), compiled to WebAssembly (Kotlin/Wasm, wasmJs) with Compose.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("tubeless")
        browser { commonWebpackConfig { outputFileName = "tubeless.js" } }
        binaries.executable()
    }
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlin.js.ExperimentalWasmJsInterop",
        )
    }
    sourceSets {
        wasmJsMain {
            kotlin.srcDirs("../common/src", "../common/web", "src")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("com.squareup.okio:okio:3.10.2")
                implementation("com.squareup.okio:okio-fakefilesystem:3.10.2")
                implementation("io.coil-kt.coil3:coil-compose:3.2.0")
            }
        }
    }
}
