import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val appVersion = providers.gradleProperty("app.version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
    // the shared code (engine, library, screens) is compiled into this module as plain sources
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../common/src", "../common/jvm")
}

// Roboto, the app's typeface (one copy for every platform: common/fonts); Pretendard there is only the
// extension's Korean fallback (the desktop falls back to the system's fonts)
sourceSets["main"].resources.srcDir("../common/fonts")
tasks.named<ProcessResources>("processResources") { exclude("Pretendard*") }
// SABR (BrowserSabr): the page script and yt-dlp's solver it runs
sourceSets["main"].resources.srcDir("../common/sabr")
sourceSets["main"].resources.srcDir("../common/ejs")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    // S2: the in-process JS host (JsHost.kt, replaces the hidden Chrome): Javet's Node.js runtime, the native
    // library for the OS and CPU this is built on (each CI runner builds its own)
    implementation("com.caoccao.javet:javet:6.0.3")
    implementation("com.caoccao.javet:javet-node-" + run {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
        when {
            os.startsWith("windows") -> "windows-x86_64"
            os.startsWith("mac") -> if (arm) "macos-arm64" else "macos-x86_64"
            else -> if (arm) "linux-arm64" else "linux-x86_64"
        }
    } + ":6.0.3")
}

compose.desktop {
    application {
        mainClass = "app.tubehaus.desktop.MainKt"
        // M3 memory: G1 gives unused heap back (periodic GC when quiet, little free heap kept), ~100 MB less RSS while playing
        jvmArgs += listOf("-Xmx512m", "-XX:MaxHeapFreeRatio=20", "-XX:MinHeapFreeRatio=5", "-XX:G1PeriodicGCInterval=15000")
        nativeDistributions {
            // only the formats of the system it is built on (Compose refuses e.g. AppImage on macOS)
            val os = System.getProperty("os.name")
            when {
                os.startsWith("Windows") -> targetFormats(TargetFormat.Msi, TargetFormat.Exe)
                os.startsWith("Mac") -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            }
            packageName = "Tubeless"
            packageVersion = appVersion
            description = "YouTube on your own terms"
            vendor = "Tubeless"
            copyright = "AGPL-3.0-or-later (based on TubeHaus, GPL-3.0)"
            // libmpv-2.dll goes in resources/windows (not in git: see README)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.net.http", "jdk.unsupported", "jdk.httpserver")
            windows {
                menuGroup = "Tubeless"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "3a7f5e2c-9b1d-4c6e-8f0a-2d4b6c8e0f13"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
            // macOS: libmpv comes inside the app (resources/macos, filled by CI from Homebrew), else Homebrew's or
            // MacPorts' mpv; the app isn't signed or notarized (right-click → Open the first time)
            macOS {
                bundleID = "dev.tubeless.app"
                appCategory = "public.app-category.video"
                // macOS wants the first number above 0: 0.3.3 becomes 1.3.3 inside the bundle only (files keep 0.3.3)
                packageVersion = "1." + appVersion.substringAfter('.')
                iconFile.set(project.layout.projectDirectory.file("icon.icns"))
            }
            // Linux uses the system's libmpv (libmpv2 on Debian/Ubuntu), so nothing is bundled
            linux {
                packageName = "tubeless"
                debMaintainer = "Tubeless <tubeless@users.noreply.github.com>"
                menuGroup = "AudioVideo"
                appCategory = "Video"
                rpmLicenseType = "GPL-3.0-only"
                shortcut = true
                iconFile.set(project.layout.projectDirectory.file("src/main/resources/icon.png"))
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// macOS: Control Center and the media keys (nowplaying/NowPlaying.swift, Apple's MediaPlayer) as a small
// library loaded into the app's process, in the jar as /nowplaying/libnowplaying.dylib; elsewhere none
val nowPlayingLib = tasks.register<Exec>("nowPlayingLib") {
    val src = layout.projectDirectory.file("nowplaying/NowPlaying.swift")
    val out = layout.buildDirectory.dir("generated/nowplaying")
    val arch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"
    inputs.file(src)
    outputs.dir(out)
    onlyIf { System.getProperty("os.name").startsWith("Mac") && File("/usr/bin/swiftc").exists() }
    doFirst { out.get().dir("nowplaying").asFile.mkdirs() }
    commandLine("/usr/bin/swiftc", "-O", "-emit-library", "-target", "$arch-apple-macos12", src.asFile.path,
        "-o", out.get().file("nowplaying/libnowplaying.dylib").asFile.path)
}
sourceSets["main"].resources.srcDir(nowPlayingLib)

// macOS: the comment translator (translate/Translate.swift, Apple's own translation, macOS 26+)
// goes into the jar as /translate/translate-mac; elsewhere, or without swiftc, there is none
// and the app hides the "Translate" links
val translateHelper = tasks.register<Exec>("translateHelper") {
    val src = layout.projectDirectory.file("translate/Translate.swift")
    val out = layout.buildDirectory.dir("generated/translate")
    val arch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"
    inputs.file(src)
    outputs.dir(out)
    onlyIf { System.getProperty("os.name").startsWith("Mac") && File("/usr/bin/xcrun").exists() }
    // the macOS 26 SDK is needed; on a build machine whose default Xcode is older, a newer
    // Xcode 26 next to it is used for this one step (the rest of the build keeps the default)
    File("/Applications").listFiles { f -> f.name.matches(Regex("Xcode_2[6-9][0-9.]*\\.app")) }
        ?.maxByOrNull { it.name }?.let { environment("DEVELOPER_DIR", "${it.path}/Contents/Developer") }
    doFirst { out.get().dir("translate").asFile.mkdirs() }
    commandLine("/usr/bin/xcrun", "swiftc", "-O", "-target", "$arch-apple-macos26", src.asFile.path, "-o", out.get().file("translate/translate-mac").asFile.path)
    // no macOS 26 SDK at all: the app is built without the helper and hides the "Translate" links
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0) {
            out.get().file("translate/translate-mac").asFile.delete()
            logger.warn("translateHelper: no macOS 26 SDK here, the Mac app is built without comment translation")
        }
    }
}
sourceSets["main"].resources.srcDir(translateHelper)

// runs every engine call against YouTube: ./gradlew :desktop:engineCheck
tasks.register<JavaExec>("engineCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.tubehaus.desktop.EngineCheckKt")
}

// when YouTube stops answering the player clients in clients.json: finds the ones that still work and rewrites the list
// ./gradlew :desktop:engineRepair
tasks.register<JavaExec>("engineRepair") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.tubehaus.desktop.EngineRepairKt")
}

// signed-in Home check (from eval/signed-in-home): ./gradlew :desktop:homeCheck -Pcookies=/path/cookies.txt
// uses the owner's local cookies; never run in CI
tasks.register<JavaExec>("homeCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.tubehaus.desktop.HomeCheckKt")
    args((findProperty("cookies") ?: "${System.getProperty("user.home")}/youtube-app/eval/cookies.txt").toString())
}

// S3's offline sign-in checks (cookie jar, SAPISIDHASH, accounts, signed-in rule): ./gradlew :desktop:accountCheck
tasks.register<JavaExec>("accountCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.tubehaus.desktop.AccountCheckKt")
}
