plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Build number = commits in this repo, so the number shown in Settings matches the
 * project history instead of a hand-maintained counter.
 */
val buildNumber: Int = try {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (_: Exception) {
    0
}

android {
    namespace = "fukuro"
    compileSdk = 35

    defaultConfig {
        // The app's identity on the device. Changing it makes Android treat the
        // build as a different app (fresh install, no data carried over), so leave
        // it alone once published. Android requires at least two segments.
        applicationId = "nl.codefin.fukuro"
        minSdk = 26
        targetSdk = 35
        // versionCode must never go down or Android refuses to install over the
        // existing app, so it stays a plain counter; the human-facing build number
        // is the commit count below.
        versionCode = 73
        versionName = "1.9.1"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        // where the in-app update check looks for releases; change it in a fork
        buildConfigField("String", "UPDATE_REPO", "\"FinnWiel/fukuro\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true // for the version shown in Settings
    }
}

dependencies {
    // Android + Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Playback (also drives Android Auto)
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")

    // Networking, storage, images
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1") // on-device library folder (SAF)

    // home screen widgets (Compose-flavoured RemoteViews)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
}
