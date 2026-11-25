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
        versionCode = 4
        versionName = "id4-id5-surfacetexture-20261125-v4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Use the same tracked debug certificate on every CI build of this isolated module.
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("app/x14u-debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release { isMinifyEnabled = false }
    }
}

kotlin { jvmToolchain(17) }
