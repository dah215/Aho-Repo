buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Nạp trực tiếp class chạy plugin
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Áp dụng plugin Cloudstream thủ công để fix lỗi không tìm thấy ID
apply(plugin = "com.lagradost.cloudstream3.gradle")

android {
    namespace = "com.boctem"
    // compileSdk thay thế cho compileSdkVersion trong các bản mới
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
    implementation(kotlin("stdlib"))
    
    // Các thư viện lõi của Cloudstream
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.github.Lagradost:NiceHttp:0.4.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
