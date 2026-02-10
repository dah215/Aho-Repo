plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Sử dụng phiên bản master-SNAPSHOT nhưng lần này thông qua mapping ở settings
    id("com.lagradost.cloudstream3.gradle") version "master-SNAPSHOT"
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
    implementation(kotlin("stdlib"))
    
    // Thư viện cần thiết cho Cloudstream
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.github.Lagradost:NiceHttp:0.4.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
