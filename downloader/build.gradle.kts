// "Belle Downloader": OPTIONAL tiny companion app. It is the only part of the
// project that has the INTERNET permission. It downloads model files and
// hands them to the main app (which has no network access at all).
plugins { id("com.android.application") }

android {
    namespace = "app.belle.downloader"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.belle.downloader"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    sourceSets { getByName("main") { kotlin.directories.add("../shared/src/main/java") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies { }  // intentionally none
