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
    
    // Đã xóa kotlinOptions để tránh lỗi version
}

dependencies {
    val cloudstream by configurations
    
    // SỬA: Đổi "pre-release" thành "master-SNAPSHOT"
    cloudstream("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    // SỬA: Đổi "pre-release" thành "master-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    // Thêm OkHttp để hỗ trợ Requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Jsoup cho phân tích HTML
    implementation("org.jsoup:jsoup:1.17.2")
}
