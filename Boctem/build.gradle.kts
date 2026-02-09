plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.boctem" // Viết thường toàn bộ cho chuẩn
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.github.recloudstream:cloudstream:pre-release")
    
    // XÓA DÒNG JSOUP ĐI VÌ CLOUDSTREAM ĐÃ CÓ SẴN
    // implementation("org.jsoup:jsoup:1.17.2") 
}
