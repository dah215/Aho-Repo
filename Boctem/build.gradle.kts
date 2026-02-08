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
    compileSdk = 34  // Ổn định, không dùng 35 beta
    
    defaultConfig {
        minSdk = 21
        // KHÔNG DÙNG targetSdk ở đây nữa (deprecated)
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    // Thêm nếu cần test
    testOptions {
        targetSdk = 34
    }
    
    lint {
        targetSdk = 34
    }
}

dependencies {
    // Cách 1: Dùng master-SNAPSHOT (luôn mới nhất, có thể unstable)
    // compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    
    // Cách 2: Dùng commit hash cụ thể (ỔN ĐỊNH NHẤT - thay bằng hash mới nhất)
  ##  compileOnly("com.github.recloudstream:cloudstream:9c6c9c7e6a")  // Hash từ tháng 2/2025
    
    // Cách 3: Dùng version tag nếu có
    // compileOnly("com.github.recloudstream:cloudstream:4.2.1")
    
    implementation("org.jsoup:jsoup:1.17.2")
}
