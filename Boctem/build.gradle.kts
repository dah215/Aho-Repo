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
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.github.recloudstream:cloudstream:pre-release")
    
    // THÊM LẠI DÒNG NÀY ĐỂ FIX LỖI BUILD
    implementation("org.jsoup:jsoup:1.17.2")
}
