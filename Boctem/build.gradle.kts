plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Gọi plugin Cloudstream (đã được định nghĩa ở file settings phía trên)
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.boctem"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib"))

    // Fix lỗi CloudstreamPlugin & NiceHttp
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.github.Lagradost:NiceHttp:0.4.1")
    
    // Thư viện hỗ trợ lấy dữ liệu web
    implementation("org.jsoup:jsoup:1.17.2")
}
