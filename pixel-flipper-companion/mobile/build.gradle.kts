plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bluetier.flippercompanion.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bluetier.flippercompanion"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Debug builds are signed with a keystore committed to this repo, so every
    // build carries the same signature whoever makes it. Without it AGP
    // generates a fresh debug key per machine, which means an APK from one CI
    // run cannot be installed over one from another: adb reports
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only way out is uninstalling
    // first, losing app data each update.
    //
    // This file is not a secret. The credentials below are the standard Android
    // debug ones, a debug key cannot be used to publish to Play, and the point
    // of checking it in is precisely that everyone shares it. Release signing
    // must never work this way.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
