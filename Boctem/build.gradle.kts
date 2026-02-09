version = 1

cloudstream {
    description = "Vietnamese Anime (Anime Vietsub)"
    language = "vi"
    authors = listOf("CloudStream Builder")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://boctem.com/wp-content/uploads/2022/06/x-logo-2.png"
}

android {
    namespace = "com.boctem"  // ← DÒNG QUAN TRỌNG!
    compileSdk = 34
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.github.recloudstream:cloudstream:pre-release")
    
    implementation("org.jsoup:jsoup:1.17.2")
}
