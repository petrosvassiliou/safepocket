plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.safepocket.android"
    compileSdk = 36 // Android 17+ SDK target

    defaultConfig {
        applicationId = "com.safepocket.vault"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Single universal native target supporting Smartphones, Foldables (ARM64),
        // and Googlebook OS laptops (ARM64 + x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SAFEPOCKET_KEYSTORE_PATH") ?: "keystore/release.jks")
            storePassword = System.getenv("SAFEPOCKET_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("SAFEPOCKET_KEY_ALIAS") ?: "safepocket-release"
            keyPassword = System.getenv("SAFEPOCKET_KEY_PASSWORD") ?: ""

            // Mandatory modern APK Signature Schemes for Play Protect compliance & incremental streaming
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.window)
}
