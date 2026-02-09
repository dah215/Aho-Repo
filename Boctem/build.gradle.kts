plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.boctem"

    // Các giá trị này có thể bỏ nếu muốn kế thừa từ root,
    // giữ lại để tránh lỗi khi AGP thay đổi hành vi
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    val csVersion = "master-SNAPSHOT"

    compileOnly("com.github.recloudstream:cloudstream:$csVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
