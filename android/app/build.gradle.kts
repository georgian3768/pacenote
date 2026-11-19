import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Room generates the DAO implementations at build time.
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.pocketmechanic.spike"
    compileSdk = 33

    defaultConfig {
        // Renamed for the public 1.0 release. The Kotlin package stays
        // com.pocketmechanic.spike on purpose: only the install identity and its
        // data directory need to change, and a package-wide refactor buys
        // nothing user-visible.
        applicationId = "com.pacenote.app"
        minSdk = 29
        targetSdk = 33
        versionCode = 2
        versionName = "1.0"
    }

    // Release signing is configured outside the repo: android/keystore.properties
    // is gitignored, and a checkout without it still builds (unsigned) so nobody
    // needs the private key to compile.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Migration build: debuggable (so `run-as` can seed the database) but
        // signed with the release key, so the release APK upgrades over it and
        // keeps the data. Never distributed.
        create("staging") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.findByName("release")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The floating bubble is being rewritten in Compose behind a debug flag;
    // the legacy View overlay stays the default until the rewrite is driven.
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Must match Kotlin 1.9.22 (root build.gradle.kts).
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose 1.5.x is the last line that builds against compileSdk 33.
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    // A WindowManager overlay has no Activity, so the ComposeView needs its
    // lifecycle/saved-state/view-model owners wired by hand.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(project(":shiftassist"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core:1.10.1")
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.room:room-ktx:2.5.2")
    kapt("androidx.room:room-compiler:2.5.2")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}

// Compose 1.5.4 pulls emoji2 1.4.0, which requires compileSdk 34. This project
// is pinned to 33 (AGP 8.1.4 / the installed platform), and Compose only uses
// emoji2 for its fallback text path, so pin the last 33-compatible release.
configurations.configureEach {
    resolutionStrategy {
        force("androidx.emoji2:emoji2:1.3.0")
        force("androidx.emoji2:emoji2-views-helper:1.3.0")
    }
}
