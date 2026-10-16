import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// So the copyIconHelperApk task below can read :iconhelper's configured output directory.
evaluationDependsOn(":iconhelper")

// --- Release signing: read from keystore.properties if present (git-ignored) ------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Toàn bộ UI giờ dựng bằng code (xem MainActivity.kt) — không còn layout XML nào
        // để ViewBinding sinh class, nên tắt đi cho gọn.
        buildConfig = true
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
        // De go loi nhat: khong minify, log day du, cai song song ban release (duoi .debug).
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
            // Co keystore.properties thi ky bang key that; khong co thi ky tam bang debug key
            // de van cai thu duoc (KHONG dung ban nay de phat hanh).
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
}

// ------------------------------------------------------------------------------------------
// Bundle the headless :iconhelper APK as a raw asset so MainActivity can push + `pm install`
// it onto the TV on demand (see bulkFetchIcons in MainActivity.kt). Rebuilding :iconhelper's
// debug APK and copying it into app/src/main/assets runs automatically before every build.
// ------------------------------------------------------------------------------------------
val copyIconHelperApk by tasks.registering(Copy::class) {
    dependsOn(":iconhelper:assembleDebug")
    from(project(":iconhelper").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("iconhelper-debug.apk")
        rename { "iconhelper.apk" }
    }
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(copyIconHelperApk) }
