plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.min.adb.iconhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.min.adb.iconhelper"
        minSdk = 21
        // Deliberately staying on the legacy (pre-scoped) storage model so writing to /sdcard
        // just needs the normal WRITE_EXTERNAL_STORAGE permission, granted silently via
        // `pm grant`/install-time grant - no runtime permission dialog ever appears on the TV.
        targetSdk = 28
        versionCode = 3
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // No keystore needed for this internal helper - it's never published, only pushed
            // and installed by the main app itself.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
    }
}
