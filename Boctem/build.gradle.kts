// Sử dụng buildscript để nạp plugin từ JitPack một cách thủ công
buildscript {
    repositories {
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Apply plugin Cloudstream theo cách thủ công để tránh lỗi "Plugin not found"
apply(plugin = "com.lagradost.cloudstream3.gradle")

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
    implementation(kotlin("stdlib"))
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.github.Lagradost:NiceHttp:0.4.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
