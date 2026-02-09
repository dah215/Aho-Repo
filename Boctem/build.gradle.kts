plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Nếu bạn cần plugin cloudstream cho module này, giữ dòng dưới;
    // nếu plugin không có sẵn trong pluginManagement, build sẽ báo lỗi.
    // id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.boctem"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    val csVersion = "master-SNAPSHOT"

    // nếu cloudstream artifact cần ở dạng compileOnly / implementation
    compileOnly("com.github.recloudstream:cloudstream:$csVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
