plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Single-sourced from the root build script — see the comment on `appVersionLabel` there.
val appVersionLabel: String by rootProject.extra
val appVersionNumber = appVersionLabel.removePrefix("v")

android {
    namespace = "com.diving.replay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diving.replay"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionNumber.toInt()
        versionName = appVersionNumber
        // Launcher label. Kept short so One UI's home screen doesn't truncate the version away.
        resValue("string", "app_name", "Rep $appVersionLabel")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Media3 Transformer / some ExoPlayer surfaces are @UnstableApi (opt-in = ERROR).
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true // the UI shows BuildConfig.VERSION_NAME
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        // The buffer/timeline logic under test touches android.util.Log; stubbing it out keeps
        // these as plain JVM tests (no device or emulator needed).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX — segment rolling buffer + live preview
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Media3 — ExoPlayer (rewind scrub) + Transformer (export merge/trim)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)

    // Wear Data Layer (phone side listens for watch REC/STOP markers)
    implementation(libs.play.services.wearable)

    // Settings persistence (resolution / bitrate / buffer length)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
}
