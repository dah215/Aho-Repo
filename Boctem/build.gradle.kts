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
    
    // Dùng implementation để đảm bảo thư viện có mặt khi biên dịch
    cloudstream("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
