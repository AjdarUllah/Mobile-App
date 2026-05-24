plugins {
    id("com.android.application")
}

android {
    namespace = "com.ajdar.magnetometerrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ajdar.magnetometerrecorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 60
        versionName = "6.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
