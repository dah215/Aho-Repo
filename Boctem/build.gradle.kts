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
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    val cloudstream by configurations
    
    // Dùng cloudstream cho plugin system
    cloudstream("com.github.recloudstream:cloudstream:pre-release")
    
    // Dùng compileOnly để fix lỗi "Cannot access class Requests" khi Build
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    
    // Thêm OkHttp để hỗ trợ Requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Jsoup cho phân tích HTML
    implementation("org.jsoup:jsoup:1.17.2")
}
