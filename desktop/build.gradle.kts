import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Desktop (Windows first, Linux for development/tests) port of Milky Moo's Assistant.
// It REUSES the Android app's platform-free Kotlin by compiling those files straight from app/src/main/java (list below),
// so every fix there reaches the desktop too. Android-only APIs those files touch (Context, SharedPreferences, Log, ...)
// are covered by tiny shims in src/main/kotlin/android/ (see docs/features/windows.md, "How the port reuses the phone code").
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val appVersionCode: Int = Regex("versionCode\\s*=\\s*(\\d+)").find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1].toInt()
val appVersion: String = rootProject.file("app/build.gradle.kts").readText()
    .let { Regex("versionName = \"([^\"]+)\"").find(it)!!.groupValues[1] }
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "dev" }

/** Android app files the desktop build compiles unchanged (paths relative to app/src/main/java). */
val sharedFromApp = listOf(
    "app/belle/assistant/core/**",
    "app/belle/assistant/mesh/**",
    "app/belle/assistant/book/BookText.kt", "app/belle/assistant/book/Epub.kt", "app/belle/assistant/book/Library.kt",
    "app/belle/assistant/book/BookCommands.kt", "app/belle/assistant/book/BookImport.kt",
    "app/belle/assistant/threed/Big.kt", "app/belle/assistant/threed/FlowSampler.kt", "app/belle/assistant/threed/ModelInstaller.kt",
    "app/belle/assistant/threed/Ort.kt", "app/belle/assistant/threed/P3Math.kt", "app/belle/assistant/threed/Pixal3DCheckpoint.kt",
    "app/belle/assistant/threed/Run3DPlan.kt", "app/belle/assistant/threed/ShapeDecoder.kt", "app/belle/assistant/threed/TextureBake.kt",
    "app/belle/assistant/models/Fetcher.kt", "app/belle/assistant/net/Net.kt", "app/belle/assistant/speech/SpeechChunks.kt",
    "app/belle/assistant/Prefs.kt", "app/belle/assistant/ProfileStore.kt", "app/belle/assistant/SettingsRev.kt",
    "app/belle/assistant/ui/Components.kt", "app/belle/assistant/ui/ProgressUi.kt",
    "app/belle/assistant/models/ModelFiles.kt", "app/belle/assistant/models/ModelImport.kt", "app/belle/assistant/models/Downloads.kt",
    "app/belle/assistant/avatar/Avatar3DFiles.kt", "app/belle/assistant/avatar/Avatar3DRenderer.kt", "app/belle/assistant/threed/Port3D.kt", "app/belle/assistant/threed/Pixal3D.kt",
    "app/belle/assistant/service/AssistantRuntime.kt", "app/belle/assistant/service/BookReader.kt", "app/belle/assistant/service/ReminderStore.kt",
    "app/belle/assistant/speech/SpeechEngines.kt", "app/belle/assistant/speech/Sounds.kt", "app/belle/assistant/speech/VoicePreview.kt",
    "app/belle/assistant/avatar/LipSync.kt", "app/belle/assistant/llm/LlamaEngine.kt",
    "app/belle/assistant/ui/AvatarPages.kt", "app/belle/assistant/ui/AvatarStudio.kt", "app/belle/assistant/ui/BackgroundPage.kt",
    "app/belle/assistant/ui/BooksPage.kt", "app/belle/assistant/ui/BuildLogPanel.kt", "app/belle/assistant/ui/HomeAndChat.kt",
    "app/belle/assistant/ui/Install3DPanel.kt", "app/belle/assistant/ui/MemoryPage.kt", "app/belle/assistant/ui/ModelsPage.kt",
    "app/belle/assistant/ui/NameStyleSection.kt", "app/belle/assistant/ui/PromptEditor.kt", "app/belle/assistant/ui/SettingsKit.kt",
    "app/belle/assistant/ui/SettingsScreen.kt", "app/belle/assistant/ui/Theme.kt", "app/belle/assistant/ui/UiState.kt",
    "app/belle/assistant/threed/Pixal3DJob.kt", "app/belle/assistant/threed/Pixal3DInstall.kt",
    "app/belle/assistant/avatar/RigStore.kt", "app/belle/assistant/avatar/EditorViews.kt", "app/belle/assistant/avatar/Cutout.kt", "app/belle/assistant/avatar/AvatarView.kt",
    "app/belle/assistant/avatar/Backgrounds.kt", "app/belle/assistant/ui/NameArt.kt",
)

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
// Copy (not move) the shared app files into build/ so the desktop compiles them unchanged, next to its own sources.
// sherpa-onnx's Kotlin API classes from the same AAR the phone uses (they run on the desktop JVM against the desktop
// sherpa-onnx JNI library of the same version; android.content.res.AssetManager is one of our shims).
val sherpaAar = rootProject.file("app/libs/sherpa-onnx-1.13.8.aar")
val sherpaClasses by tasks.registering(Copy::class) {
    from(zipTree(sherpaAar)) { include("classes.jar") }
    into(layout.buildDirectory.dir("sherpa"))
    rename { "sherpa-onnx-1.13.8-classes.jar" }
}
val syncSharedSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") { include(sharedFromApp) }
    from("../shared/src/main/java")
    into(layout.buildDirectory.dir("generated/shared-src"))
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir(syncSharedSources)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20250517")
    implementation(files(layout.buildDirectory.file("sherpa/sherpa-onnx-1.13.8-classes.jar")).builtBy(sherpaClasses))
    // MP3 and Ogg Vorbis decoding for Java Sound (voice samples, custom sounds); LGPL-2.1, see THIRD_PARTY_NOTICES
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")
    // OpenGL for the 3D avatar (the renderer is the Android one, over the android.opengl shims)
    val lwjgl = "3.3.6"
    val os = System.getProperty("os.name").lowercase()
    val natives = listOfNotNull("natives-windows", if (os.contains("linux")) "natives-linux" else null)
    for (m in listOf("lwjgl", "lwjgl-opengl", "lwjgl-glfw")) {
        implementation("org.lwjgl:$m:$lwjgl")
        for (n in natives) runtimeOnly("org.lwjgl:$m:$lwjgl:$n")
    }
    // Windows APIs (keep awake, DPAPI key protection) through JNA
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")   // memory store (same schema as the phone)
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    System.getProperties().forEach { (k, v) -> if (k.toString().startsWith("desk.") || k.toString().startsWith("p3d.")) systemProperty(k.toString(), v.toString()) }
    // native libraries for the tests that load them (speech, chat, Photo -> 3D): a folder with the CPU set
    System.getenv("BELLE_NATIVES")?.let { systemProperty("belle.natives", it); systemProperty("java.library.path", it) }
}

val genVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    val v = appVersion; val s = gitSha; val code = appVersionCode
    inputs.property("version", v); inputs.property("sha", s); inputs.property("code", code)   // regenerate per commit
    doLast {
        val f = out.get().file("app/belle/desktop/BuildInfo.kt").asFile; f.parentFile.mkdirs()
        f.writeText("package app.belle.desktop\n\n/** Generated by desktop/build.gradle.kts. */\nobject BuildInfo {\n    const val VERSION = \"$v\"\n    const val GIT_SHA = \"$s\"\n}\n")
        // the same BuildConfig the phone app's shared screens read (version label in Settings and the build log)
        val b = out.get().file("app/belle/assistant/BuildConfig.kt").asFile; b.parentFile.mkdirs()
        b.writeText("package app.belle.assistant\n\n/** Generated by desktop/build.gradle.kts (the Windows program's twin of the Android BuildConfig). */\nobject BuildConfig {\n" +
            "    const val VERSION_NAME = \"$v\"\n    const val VERSION_CODE = $code\n    const val GIT_SHA = \"$s\"\n    const val DEBUG = false\n    const val BUILD_TYPE = \"release\"\n    const val APPLICATION_ID = \"app.belle.assistant\"\n}\n")
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(genVersion)
// the phone app's drawables the shared screens use (R.drawable.*), on the desktop classpath under drawable/
val syncDrawables by tasks.registering(Sync::class) {
    from("../app/src/main/res/drawable-nodpi") { include("avatar_default.webp"); into("drawable") }
    from("../app/src/main/res/drawable") { include("ic_mic.xml"); into("drawable") }
    from("icons") { include("*.png"); into("icons") }   // window and tray icons
    into(layout.buildDirectory.dir("generated/res"))
}
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/res"))
tasks.named("processResources") { dependsOn(syncDrawables) }

// EVERY file of the phone app's assets/ folder (default avatars, wake-word model, cut-out model, Photo → 3D model list,
// voice samples) goes into the program: resources/common/assets ends up in <install>/app/resources/assets for the MSI,
// the setup program, the portable folder and dev runs; DesktopContext.assets reads it through the AssetManager shim.
// The list of what must be there (path + size) rides on the classpath, so --selftest and BundledAssetsTest can prove it.
val appAssets = rootProject.file("app/src/main/assets")
val syncAppAssets by tasks.registering(Sync::class) {
    from(appAssets)
    into(project.layout.projectDirectory.dir("resources/common/assets"))
}
val genAssetList by tasks.registering {
    val out = layout.buildDirectory.file("generated/assetlist/bundled-assets.txt")
    inputs.dir(appAssets); outputs.file(out)
    doLast {
        val lines = appAssets.walkTopDown().filter { it.isFile }.map { it.relativeTo(appAssets).invariantSeparatorsPath + "\t" + it.length() }.sorted().toList()
        // the models aren't in git (scripts/fetch_models.sh / the CI step fetch them): never build a program without them
        val required = listOf("models/kws/encoder.int8.onnx", "models/kws/decoder.onnx", "models/kws/joiner.int8.onnx", "models/kws/tokens.txt",
            "models/kws/bpe.vocab", "models/u2netp.onnx", "default_avatars/bob.glb", "default_avatars/boob_dragon.glb", "default_avatars/boob_dragon.webp", "pixal3d_models.json")
        val missing = required.filter { appAssets.resolve(it).length() == 0L }
        check(missing.isEmpty()) { "app/src/main/assets is missing $missing: run scripts/fetch_models.sh first" }
        out.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
    }
}
sourceSets["main"].resources.srcDir(files(layout.buildDirectory.dir("generated/assetlist")).builtBy(genAssetList))
tasks.matching { it.name.startsWith("prepareAppResources") || it.name == "run" || it.name == "test" }.configureEach { dependsOn(syncAppAssets) }

compose.desktop {
    application {
        mainClass = "app.belle.desktop.MainKt"
        jvmArgs += listOf("-Xss8m", "-XX:+UseZGC", "-Dfile.encoding=UTF-8")
        // sherpa-onnx's classes call System.loadLibrary, which only searches the path the JVM started with
        if (System.getenv("BELLE_NATIVES") == null) jvmArgs += "-Djava.library.path=\$APPDIR/resources/natives"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "MilkyMoosAssistant"
            packageVersion = appVersion.substringBefore('-').let { if (it.startsWith("0.")) "1." + it.removePrefix("0.") else it }
            description = "Milky Moo's Assistant: a private, fully local voice assistant"
            vendor = "Milkymoomoo"
            copyright = "Apache-2.0 (code); see THIRD_PARTY_NOTICES"
            licenseFile.set(rootProject.file("LICENSE").takeIf { it.exists() } ?: rootProject.file("tools/publish/repo_files/LICENSE"))
            modules("java.instrument", "java.management", "jdk.management", "java.naming", "java.sql", "jdk.unsupported", "java.net.http", "jdk.crypto.ec", "jdk.accessibility")
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "Milky Moo's Assistant"
                shortcut = true; menu = true; dirChooser = true; perUserInstall = true
                upgradeUuid = "6c1f1b1e-4f7e-4b8e-9a0e-2a5b6f0b7d11"
                iconFile.set(project.file("icons/app.ico"))
            }
            linux { iconFile.set(project.file("icons/app.png")) }
        }
    }
}

// Development runs (./gradlew :desktop:run): BELLE_NATIVES = a folder with the native libraries, BELLE_HOME = a data folder
tasks.withType<JavaExec>().configureEach {
    System.getenv("BELLE_NATIVES")?.let { systemProperty("belle.natives", it); systemProperty("java.library.path", it) }
    System.getenv("BELLE_HOME")?.let { systemProperty("belle.home", it) }
}
