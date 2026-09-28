plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mobilegamma.cakesync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilegamma.cakesync"
        minSdk = 29
        targetSdk = 35
        // CI sets GITHUB_RUN_NUMBER, so each published build installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Shared signing key, restored in CI from the CAKESYNC_KEYSTORE_BASE64 /
    // CAKESYNC_KEYSTORE_PASSWORD GitHub secrets, so every build has the same SHA-1
    // (registered in Google Cloud for Drive sign-in). Without them, builds fall back
    // to the local debug key.
    val sharedKeystore = file("cakesync.jks")
    val sharedPassword = System.getenv("CAKESYNC_KEYSTORE_PASSWORD")
    if (sharedKeystore.exists() && !sharedPassword.isNullOrEmpty()) {
        signingConfigs {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = sharedPassword
                keyAlias = "cakesync"
                keyPassword = sharedPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.2")

    // On-device photo labelling (bundled model, works offline)
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:object-detection:17.0.2")

    // Google sign-in / authorization for the Drive API
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
}
