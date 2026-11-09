plugins { id("com.android.application") }
android {
    namespace = "com.forest.android.speed"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.forest.android.speed.payload"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
