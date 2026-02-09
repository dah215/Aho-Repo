import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    // Namespace phải khớp với package trong code
    namespace = "com.boctem"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        // targetSdk không bắt buộc với library nhưng nên có
        @Suppress("DEPRECATION")
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Ép kiểu JVM 1.8 cho Kotlin bên trong block android
    kotlinOptions {
        jvmTarget = "1.8"
        // Bùa hộ mệnh để sửa lỗi Metadata 2.3.0 của CloudStream
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

// Đảm bảo tất cả các task biên dịch đều nhận cấu hình skip metadata
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    val cloudstream by configurations
    val csVersion = "master-SNAPSHOT"

    // Cấu hình bắt buộc cho CloudStream Plugin
    cloudstream("com.github.recloudstream:cloudstream:$csVersion")
    compileOnly("com.github.recloudstream:cloudstream:$csVersion")
    
    // Các thư viện hỗ trợ
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
