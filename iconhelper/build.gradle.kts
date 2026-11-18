plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val helperVersion = providers.gradleProperty("iconHelperVersion").get().toInt()

android {
    namespace = "com.min.adb.iconhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.min.adb.iconhelper"
        minSdk = 21
        // Old targetSdk keeps the install silent: no runtime permission dialog on the device.
        targetSdk = 28
        versionCode = helperVersion
        versionName = "1.$helperVersion"
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
    }
}
