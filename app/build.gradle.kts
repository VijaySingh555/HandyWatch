plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.rathour.handywatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.rathour.handywatch"
        minSdk = 21          // Android 5.0; the watch is Android 8, the phone Android 14/15
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // signed with the key in the repository's secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

// no libraries: Android's own classes only (smaller, and nothing to keep up to date)
dependencies { }
