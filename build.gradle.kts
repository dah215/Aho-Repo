import groovy.json.JsonBuilder

plugins {
    // declare plugins with versions but don't apply at root
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

subprojects {
    // Không khai báo repositories ở đây (đã chuyển vào settings.gradle.kts)
    // Áp dụng plugin Android chỉ cho những module có mã nguồn Android
    if (file("${project.projectDir}/src/main").exists()) {
        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.android")
        // plugin tuỳ chỉnh: cố gắng apply nếu có trong classpath, không làm sập script nếu không có
        try {
            apply(plugin = "com.lagradost.cloudstream3.gradle")
        } catch (_: Throwable) {
            // plugin không có -> bỏ qua
        }
    }
}

/*
  Tạo task "make" để tương thích với CI/command gọi "gradle make".
  Task này sẽ kích hoạt assemble cho tất cả subprojects có task 'assemble'.
*/
tasks.register("make") {
    group = "build"
    description = "Compatibility task: assemble all Android modules (alias for CI 'make')"
    dependsOn(subprojects.flatMap { sp ->
        sp.tasks.matching { it.name == "assemble" }.toList()
    })
}

// Task để tạo plugins.json từ tất cả các build.json
tasks.register("makePluginsJson") {
    group = "build"
    description = "Generate plugins.json from all build.json files"
    
    doLast {
        val pluginsList = mutableListOf<Map<String, Any>>()
        
        subprojects.forEach { subproject ->
            val buildJsonFile = file("${subproject.projectDir}/build.json")
            if (buildJsonFile.exists()) {
                val buildJson = groovy.json.JsonSlurper().parse(buildJsonFile) as Map<*, *>
                
                val outputDir = file("${subproject.buildDir}/outputs/apk/release")
                val cs3Files = outputDir.listFiles()?.filter { it.extension == "cs3" }
                
                if (!cs3Files.isNullOrEmpty()) {
                    val cs3File = cs3Files.first()
                    
                    pluginsList.add(mapOf(
                        "name" to buildJson["name"],
                        "url" to cs3File.name,
                        "version" to (buildJson["version"] ?: 1),
                        "description" to (buildJson["description"] ?: ""),
                        "authors" to (buildJson["authors"] ?: listOf<String>()),
                        "status" to (buildJson["status"] ?: 3),
                        "tvTypes" to (buildJson["tvTypes"] ?: listOf<String>()),
                        "language" to (buildJson["language"] ?: ""),
                        "iconUrl" to (buildJson["iconUrl"] ?: "")
                    ))
                }
            }
        }
        
        val pluginsJsonFile = file("${rootProject.buildDir}/plugins.json")
        pluginsJsonFile.parentFile.mkdirs()
        pluginsJsonFile.writeText(JsonBuilder(pluginsList).toPrettyString())
        
        println("Generated plugins.json with ${pluginsList.size} plugins")
    }
}

// Task để tạo repository.json
tasks.register("makeRepositoryJson") {
    group = "build"
    description = "Generate repository.json"
    
    doLast {
        val repoData = mapOf(
            "name" to "BocTem Repository",
            "description" to "Vietnamese anime streaming repository",
            "manifestVersion" to 1,
            "pluginLists" to listOf(
                "https://raw.githubusercontent.com/YOUR_USERNAME/YOUR_REPO/Builds/plugins.json"
            )
        )
        
        val repoJsonFile = file("${rootProject.buildDir}/repository.json")
        repoJsonFile.parentFile.mkdirs()
        repoJsonFile.writeText(JsonBuilder(repoData).toPrettyString())
        
        println("Generated repository.json")
    }
}

// Làm cho build task tự động tạo các file JSON
tasks.named("make") {
    finalizedBy("makePluginsJson", "makeRepositoryJson")
}
