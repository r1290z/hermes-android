import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The keystore lives next to the project on the build host, never in git.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.evgen.hermes"
    // 36 (Android 16) is the first release with promoted Live Updates and
    // Notification.ProgressStyle. targetSdk stays at 34 on purpose: 35 forces
    // edge-to-edge, which would move every layout under the system bars.
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.evgen.hermes"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "5.4"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
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
            isShrinkResources = false
            // No debug fallback on purpose: a release APK signed with the debug key
            // looks fine and installs on nothing that already has the app. Missing
            // keystore.properties must fail the build, not degrade it.
            signingConfig = signingConfigs.findByName("release")
                ?: throw GradleException(
                    "keystore.properties отсутствует — release нельзя подписать. " +
                        "Ключ лежит в ~/.hermes/secrets/hermes-android.{jks,properties}, " +
                        "build.sh копирует его на билд-хост."
                )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The Shizuku shell bridge is an AIDL service (Shizuku.newProcess is private
    // in the public API), so AIDL compilation has to be on.
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Native chat needs a real HTTP client: streaming SSE cannot go through a
    // WebView, and the app now talks to the API itself.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Shizuku: install our own update as shell, so the phone never asks for the
    // per-source sideload permission and the user skips the installer screen.
    // The `provider` artifact registers the binder bridge the manifest declares.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
