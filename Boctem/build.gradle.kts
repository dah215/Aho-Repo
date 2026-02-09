plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.boctem"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    val cloudstream by configurations
    // Sử dụng bản master-SNAPSHOT cho plugin system
    cloudstream("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    // Ép kiểu implementation để trình biên dịch thấy class Requests và OkHttp
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
