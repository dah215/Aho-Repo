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

    // THÊM ĐOẠN NÀY VÀO ĐÂY
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    val cloudstream by configurations
    val csVersion = "master-SNAPSHOT"

    cloudstream("com.github.recloudstream:cloudstream:$csVersion")
    compileOnly("com.github.recloudstream:cloudstream:$csVersion")
    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
