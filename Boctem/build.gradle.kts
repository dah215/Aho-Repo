version = 1

cloudstream {
    description = "Vietnamese Anime (Anime Vietsub)"
    language = "vi"
    authors = listOf("CloudStream Builder")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://boctem.com/wp-content/uploads/2022/06/x-logo-2.png"
    isCrossPlatform = false
}

android {
    namespace = "com.boctem"
    compileSdk = 35
    
    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // ✅ ĐÚNG CÚ PHÁP theo hình ảnh bạn gửi
    implementation("com.github.recloudstream.cloudstream:library:v4.6.0")
    
    implementation("org.jsoup:jsoup:1.17.2")
}
