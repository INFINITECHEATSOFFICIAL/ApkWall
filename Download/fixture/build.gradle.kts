plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.guardfixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.guardfixture"
        minSdk = 21
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
