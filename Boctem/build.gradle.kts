android {
    namespace = "com.boctem"
}

dependencies {
    val cloudstream by configurations
    val csVersion = "master-SNAPSHOT"

    // Đóng gói plugin
    cloudstream("com.github.recloudstream:cloudstream:$csVersion")
    
    // Hỗ trợ biên dịch
    compileOnly("com.github.recloudstream:cloudstream:$csVersion")
    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
}
