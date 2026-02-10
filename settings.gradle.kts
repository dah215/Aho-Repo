pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io") // Dòng này giúp tìm thấy plugin Cloudstream
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // Dòng này giúp tìm thấy các thư viện như NiceHttp
    }
}

rootProject.name = "Aho-Repo"
include(":Boctem")
