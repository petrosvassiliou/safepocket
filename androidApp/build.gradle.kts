plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
}

val targetAndroidSdk = (System.getenv("SAFEPOCKET_COMPILE_SDK") ?: "35").toInt()
val minAndroidSdk = (System.getenv("SAFEPOCKET_MIN_SDK") ?: "29").toInt()
val releaseVersionName = (project.findProperty("versionName") as String?)
    ?: System.getenv("SAFEPOCKET_RELEASE_VERSION")?.removePrefix("v")
    ?: "0.5.0-beta.4"
val releaseVersionCode = ((project.findProperty("versionCode") as String?)
    ?: System.getenv("SAFEPOCKET_VERSION_CODE")
    ?: "19").toInt()
val configuredKeystoreFile = file(System.getenv("SAFEPOCKET_KEYSTORE_PATH") ?: "keystore/release.jks")

android {
    namespace = "com.safepocket.android"
    compileSdk = targetAndroidSdk

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../i18n")
        }
    }

    defaultConfig {
        applicationId = "com.safepocket.vault"
        minSdk = minAndroidSdk
        targetSdk = targetAndroidSdk
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        // Single universal native target supporting Smartphones, Foldables (ARM64),
        // and Googlebook OS laptops (ARM64 + x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (configuredKeystoreFile.exists()) {
            create("release") {
                storeFile = configuredKeystoreFile
                storeType = "PKCS12"
                storePassword = System.getenv("SAFEPOCKET_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SAFEPOCKET_KEY_ALIAS") ?: "safepocket-release"
                keyPassword = System.getenv("SAFEPOCKET_KEY_PASSWORD") ?: ""

                // Universal APK Signature Schemes (v1 + v2 + v3) for Android 10–17 & ChromeOS PackageInstaller
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (configuredKeystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sqldelight.runtime)
}
