// GIẢI PHÁP 3: Build.gradle.kts với OkHttp thay thế
// Sử dụng file này nếu Giải pháp 1 và 2 đều thất bại

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
    
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    
    // LOẠI BỎ cloudstream và NiceHttp
    // Sẽ được load tại runtime bởi Cloudstream app
    
    // Dùng OkHttp thay vì NiceHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSoup cho HTML parsing
    implementation("org.jsoup:jsoup:1.17.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
}

// Task tùy chỉnh để tạo file .cs3 từ AAR
tasks.register<Copy>("makeCS3") {
    dependsOn("assembleRelease")
    
    val buildJson = file("build.json")
    val pluginName = if (buildJson.exists()) {
        val json = groovy.json.JsonSlurper().parse(buildJson) as Map<*, *>
        json["name"] as? String ?: "BocTem"
    } else {
        "BocTem"
    }
    
    from(layout.buildDirectory.dir("outputs/aar")) {
        include("*-release.aar")
        rename { "${pluginName}.cs3" }
    }
    into(layout.buildDirectory.dir("outputs/apk/release"))
}

tasks.named("build") {
    finalizedBy("makeCS3")
}
