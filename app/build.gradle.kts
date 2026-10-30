// AGP 9 has built-in Kotlin support, so the kotlin-android plugin must NOT be
// applied here — doing so is a hard error. Only the Compose compiler plugin is
// applied alongside it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.amrakshay.atvremote"
    // The current AndroidX generation (core 1.19, lifecycle 2.11, Compose 1.12)
    // all require 37. The SDK package is "platforms;android-37.2" — the platform
    // now carries a minor version, hence compileSdkMinor.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.amrakshay.atvremote"
        // The target handset runs Android 10 (API 29); 26 leaves room to spare
        // and nothing here needs anything newer.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    // The three BouncyCastle jars each ship these, and the merger refuses to pick.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
