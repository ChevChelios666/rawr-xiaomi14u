plugins { id("com.android.application") }

android {
    namespace = "com.x14u.scamlab"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.x14u.scamlab"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "X14U-hybrid-raw50-lab-1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}
