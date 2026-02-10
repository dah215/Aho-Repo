import groovy.json.JsonBuilder

plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

subprojects {
    if (file("${project.projectDir}/src/main").exists()) {
        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.android")
    }
}

// Task "make" để tương thích với CI
tasks.register("make") {
    group = "build"
    description = "Build all Android modules and create plugin files"
    
    dependsOn(subprojects.flatMap { sp ->
        sp.tasks.matching { it.name == "build" }.toList()
    })
    
    finalizedBy("makePluginsJson", "makeRepositoryJson")
}

// Task để tạo plugins.json
tasks.register("makePluginsJson") {
    group = "build"
    description = "Generate plugins.json from all build.json files"
    
    doLast {
        val pluginsList = mutableListOf<Map<String, Any>>()
        
        subprojects.forEach { subproject ->
            val buildJsonFile = file("${subproject.projectDir}/build.json")
            if (buildJsonFile.exists()) {
                val buildJson = groovy.json.JsonSlurper().parse(buildJsonFile) as Map<*, *>
                
                // Tìm file .cs3 trong thư mục outputs
                val outputDirs = listOf(
                    file("${subproject.layout.buildDirectory.get().asFile}/outputs/apk/release"),
                    file("${subproject.layout.buildDirectory.get().asFile}/outputs/aar")
                )
                
                var cs3File: File? = null
                for (dir in outputDirs) {
                    if (dir.exists()) {
                        val files = dir.listFiles()?.filter { 
                            it.extension == "cs3" || it.extension == "aar" 
                        }
                        if (!files.isNullOrEmpty()) {
                            cs3File = files.first()
                            // Nếu là AAR, đổi tên thành CS3
                            if (cs3File.extension == "aar") {
                                val newName = "${buildJson["name"]}.cs3"
                                val newFile = File(dir, newName)
                                cs3File.copyTo(newFile, overwrite = true)
                                cs3File = newFile
                            }
                            break
                        }
                    }
                }
                
                if (cs3File != null) {
                    pluginsList.add(mapOf(
                        "name" to (buildJson["name"] as? String ?: "Unknown"),
                        "url" to cs3File.name,
                        "version" to (buildJson["version"] as? Int ?: 1),
                        "description" to (buildJson["description"] as? String ?: ""),
                        "authors" to (buildJson["authors"] as? List<*> ?: emptyList<String>()),
                        "status" to (buildJson["status"] as? Int ?: 3),
                        "tvTypes" to (buildJson["tvTypes"] as? List<*> ?: emptyList<String>()),
                        "language" to (buildJson["language"] as? String ?: ""),
                        "iconUrl" to (buildJson["iconUrl"] as? String ?: "")
                    ))
                }
            }
        }
        
        val pluginsJsonFile = file("${rootProject.layout.buildDirectory.get().asFile}/plugins.json")
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
                "https://raw.githubusercontent.com/dah215/Aho-Repo/Builds/plugins.json"
            )
        )
        
        val repoJsonFile = file("${rootProject.layout.buildDirectory.get().asFile}/repository.json")
        repoJsonFile.parentFile.mkdirs()
        repoJsonFile.writeText(JsonBuilder(repoData).toPrettyString())
        
        println("Generated repository.json")
    }
}
