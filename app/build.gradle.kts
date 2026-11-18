import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

evaluationDependsOn(":iconhelper")

// Release signing comes from keystore.properties (git-ignored).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null
val iconHelperVersion = providers.gradleProperty("iconHelperVersion").get().toInt()

android {
    namespace = "com.min.adb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.min.adb"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Same number as :iconhelper's versionCode; the app reinstalls an older helper.
        buildConfigField("int", "ICON_HELPER_VERSION", "$iconHelperVersion")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    // Native SPAKE2 for wireless-debugging pairing (BoringSSL via prefab).
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // ------------------------------------------------------------------ DEBUG
        // No minify; installs next to release (.debug suffix).
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "ADB (debug)")
        }

        // ------------------------------------------------------------------ RELEASE
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            resValue("string", "app_name", "ADB")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without keystore.properties the debug key signs it: installable, not for release.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // BouncyCastle/Conscrypt metadata collides on merge and is unused on Android.
            "/META-INF/versions/**",
            "/META-INF/MANIFEST.MF",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.runtime.ktx)

    // Wireless-debugging pairing + TLS connect (same stack Shizuku uses).
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    testImplementation(libs.junit)
}

// Bundles the :iconhelper debug APK as an asset (pushed and installed by bulkFetchIcons).
val copyIconHelperApk by tasks.registering(Copy::class) {
    dependsOn(":iconhelper:assembleDebug")
    from(project(":iconhelper").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("iconhelper-debug.apk")
        rename { "iconhelper.apk" }
    }
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(copyIconHelperApk) }
