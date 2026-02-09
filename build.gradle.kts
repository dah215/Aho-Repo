import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    afterEvaluate {
        // Cấu hình CloudStream Extension
        extensions.configure<com.lagradost.cloudstream3.gradle.CloudstreamExtension> {
            setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dah215/Aho-Repo")
            authors = listOf("CloudStream Builder")
        }
    }

    // ÉP CẤU HÌNH CHO TẤT CẢ CÁC TÁC VỤ BIÊN DỊCH KOTLIN
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            // Bỏ qua kiểm tra phiên bản metadata để đọc được class từ CloudStream
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}
