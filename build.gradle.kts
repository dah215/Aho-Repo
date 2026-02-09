buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2") // Nâng cấp AGP
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0") // Nâng cấp lên Kotlin 2.1.0
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

    // Cấu hình ép kiểu JVM 1.8 và bỏ qua lỗi Metadata cho Kotlin 2.x
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            // Thêm cờ này để ép trình biên dịch đọc các class có metadata mới hơn
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}
