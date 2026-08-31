plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Single-sourced from the root build script — see the comment on `appVersionLabel` there.
val appVersionLabel: String by rootProject.extra
val appVersionNumber = appVersionLabel.removePrefix("v")

android {
    namespace = "com.diving.replay.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diving.replay" // must match the phone app for the Data Layer
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionNumber.toInt()
        versionName = appVersionNumber
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
