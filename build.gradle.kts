buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.1")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24") // Dùng bản 1.9.24 cực kỳ ổn định
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    afterEvaluate {
        configure<com.lagradost.cloudstream3.gradle.CloudstreamExtension> {
            setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dah215/Aho-Repo")
            authors = listOf("CloudStream Builder")
        }
    }
}
