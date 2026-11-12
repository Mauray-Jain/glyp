plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.mauray.glyp"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.mauray.glyp"
        // The Glyph SDK only runs on Nothing OS with Android 14+.
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Nothing's API key for the Glyph SDK: "test" (development) unless the build machine sets
        // glyp.nothingKey in ~/.gradle/gradle.properties. Never commit a real key.
        manifestPlaceholders["nothingKey"] = providers.gradleProperty("glyp.nothingKey").getOrElse("test")
    }

    // Release signing comes from the build machine's ~/.gradle/gradle.properties (or -P flags),
    // never from git. Without glyp.storeFile the release APK is built unsigned.
    val releaseStoreFile = providers.gradleProperty("glyp.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("glyp.storePassword").orNull
                keyAlias = providers.gradleProperty("glyp.keyAlias").orNull
                keyPassword = providers.gradleProperty("glyp.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
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

val glyphSdk = file("libs/glyph-matrix-sdk-2.0.aar")
check(glyphSdk.exists()) { "Glyph SDK missing: run ./scripts/fetch-glyph-sdk.sh first." }

dependencies {
    implementation(files(glyphSdk))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
