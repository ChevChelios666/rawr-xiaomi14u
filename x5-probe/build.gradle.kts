plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rawr.x14u.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rawr.camera.x14u.x5yuvprobe"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "x5-yuv-raw-20261124"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

kotlin { jvmToolchain(17) }
