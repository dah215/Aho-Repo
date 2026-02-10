pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            // Khi gặp plugin Cloudstream, ta chỉ định rõ artifact thực tế trên JitPack
            if (requested.id.id == "com.lagradost.cloudstream3.gradle") {
                useModule("com.github.recloudstream:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Aho-Repo"
include(":Boctem")
