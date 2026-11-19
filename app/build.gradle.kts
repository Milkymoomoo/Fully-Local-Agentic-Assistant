import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Short git commit of the build, shown in Settings (About) so you can tell which build is installed.
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "dev" }

android {
    namespace = "app.belle.assistant"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.belle.assistant"
        minSdk = 29          // Android 10
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 37       // Android 17 (current)
        // Bumped by scripts/release.sh on every release (never by hand between releases).
        versionCode = 7
        versionName = "0.1.6"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // Release key: keystore/release.jks + keystore/keystore.properties (keep both safe; you need them for every update).
    val ksProps = rootProject.file("keystore/keystore.properties")
    if (ksProps.exists()) {
        val kp = Properties().apply { ksProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(kp.getProperty("storeFile"))
                storePassword = kp.getProperty("storePassword")
                keyAlias = kp.getProperty("keyAlias")
                keyPassword = kp.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Native code is still built optimized so the LLM is usable in debug builds.
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
        getByName("release") {
            // R8 shrinks Compose & Kotlin a lot. Signed with the local debug key so it can be sideloaded;
            // swap in your own keystore for distribution.
            isMinifyEnabled = true
            isShrinkResources = false   // AGP 9.5 + ABI splits fails on shrunk resources; code shrinking still applies
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets { getByName("main") { kotlin.directories.add("../shared/src/main/java") } }

    // Reproducible-ish builds: no build timestamps / VCS info baked into the APK.
    packaging { resources { excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Compress native libs in the APK (≈ half the download size); Android extracts them on install.
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += listOf("onnx", "ort", "txt", "vocab", "json")
    }

    testOptions { unitTests.isReturnDefaultValues = true; unitTests.all { it.maxHeapSize = "3g"; System.getProperties().forEach { (k, v) -> if (k.toString().startsWith("specks.") || k.toString().startsWith("pm.") || k.toString().startsWith("rig.") || k.toString().startsWith("p3d.")) it.systemProperty(k.toString(), v.toString()) }; System.getenv("P3D_LIBS")?.let { libs -> it.systemProperty("java.library.path", libs); it.environment("LD_LIBRARY_PATH", libs); it.maxHeapSize = System.getenv("P3D_HEAP") ?: "6g" }; it.testLogging { showStandardStreams = true } } }
    // One APK per CPU type: phones get arm64-v8a only; x86_64 is for emulators.
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = true } }
    buildFeatures { compose = true; buildConfig = true }
}

// Settings screens read SharedPreferences-backed values and redraw from one counter (SettingsRev). Strong skipping would
// skip every unchanged-argument screen and memoized Section lambda, leaving switches visually stuck; keep classic skipping.
composeCompiler { featureFlags.add(org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.StrongSkipping.disabled()) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.28.0")
    // UI: Jetpack Compose + Material 3 (pure UI libraries; no network, analytics or Play Services).
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    // "Read to me": PDF text + outline, fully offline (Apache 2.0). Tests use the upstream PDFBox it was ported from.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Compose UI tests on a device/emulator (layout at large font scale, narrow widths): test-only, not in the APK
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
