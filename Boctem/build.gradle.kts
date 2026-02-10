buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Sử dụng commit hash cụ thể để tránh lỗi Snapshot trên JitPack
        classpath("com.github.recloudstream:gradle:cce1b8d84d")
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Áp dụng plugin của Cloudstream
apply(plugin = "com.lagradost.cloudstream3.gradle")

android {
    namespace = "com.boctem"
    // Sử dụng cả hai cách khai báo để đảm bảo Gradle không báo lỗi SDK
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
    
    // Các thư viện cần thiết để code không bị lỗi đỏ (Unresolved reference)
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.github.Lagradost:NiceHttp:0.4.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
