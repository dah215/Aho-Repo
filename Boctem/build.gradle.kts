version = 1

cloudstream {
    description = "Vietnamese Anime (Anime Vietsub)"
    language = "vi"
    authors = listOf("CloudStream Builder")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://boctem.com/wp-content/uploads/2022/06/x-logo-2.png"
    isCrossPlatform = true
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
    // ✅ Dùng version tag chính xác từ JitPack
    compileOnly("com.github.recloudstream:cloudstream:4.6.0") {
        isChanging = true  // Cho phép snapshot thay đổi
    }
    
    // ✅ Hoặc dùng master-SNAPSHOT nếu 4.3.0 không tồn tại
    // compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    implementation("org.jsoup:jsoup:1.17.2")
}
