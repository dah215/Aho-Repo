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
    
    // Cấu hình để build ra file .cs3
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    
    // Thư viện cần thiết cho Cloudstream
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    // Sửa version NiceHttp từ 0.4.1 thành 0.4.11 (version có sẵn trên JitPack)
    implementation("com.github.Lagradost:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.17.2")
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

// Tự động chạy makeCS3 sau khi build
tasks.named("build") {
    finalizedBy("makeCS3")
}
