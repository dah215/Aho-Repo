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
    
    // Cloudstream dependency - sử dụng commit hash hoặc tag cụ thể thay vì "pre-release"
    // Option 1: Sử dụng master-SNAPSHOT (luôn lấy latest)
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    // Option 2: Hoặc dùng commit hash cụ thể (ổn định hơn)
    // compileOnly("com.github.recloudstream:cloudstream:COMMIT_HASH")
    
    // NiceHttp - Kiểm tra lại format
    // JitPack format: com.github.User:Repo:Version
    implementation("com.github.Lagradost:NiceHttp:0.4.11")
    
    // JSoup
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

tasks.named("build") {
    finalizedBy("makeCS3")
}
