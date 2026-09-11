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

val fallbackVersionName = "1.10.35"
val releaseTagVersion = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?.takeIf { it.matches(Regex("""v\d+(\.\d+)*""")) }
    ?.removePrefix("v")
val appVersionName = releaseTagVersion ?: fallbackVersionName

fun androidVersionCode(versionName: String): Int {
    val parts = versionName.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 1_000_000 + minor * 1_000 + patch
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
        // Android installs are gated by versionCode, not versionName. Deriving it
        // from the release tag prevents tagged APKs from reusing a stale code.
        versionCode = androidVersionCode(appVersionName)
        versionName = appVersionName
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        // where the in-app update check looks for releases; change it in a fork
        buildConfigField("String", "UPDATE_REPO", "\"FinnWiel/fukuro\"")
    }

    signingConfigs {
        create("release") {
            providers.environmentVariable("FUKURO_KEYSTORE_FILE").orNull?.let {
                storeFile = file(it)
            }
            storePassword = providers.environmentVariable("FUKURO_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("FUKURO_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("FUKURO_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            // Development builds update the long-lived Fukuro Test install, never the
            // published nl.codefin.fukuro app.
            applicationIdSuffix = ".glassdev"
            versionNameSuffix = "-series-test"
            resValue("string", "app_name", "Fukuro Test")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.mediarouter:mediarouter:1.8.1")

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
