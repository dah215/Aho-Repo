import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.gradle.BaseExtension

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

// Áp dụng plugin cho Root để có thể chạy lệnh makePluginsJson
apply(plugin = "com.lagradost.cloudstream3.gradle")

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // Cấu hình Android cho tất cả module con (như boctem)
    extensions.configure<BaseExtension> {
        compileSdkVersion(34)
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    // Cấu hình CloudStream cho từng plugin
    afterEvaluate {
        extensions.findByType<com.lagradost.cloudstream3.gradle.CloudstreamExtension>()?.apply {
            setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dah215/Aho-Repo")
            authors = listOf("CloudStream Builder")
        }
    }

    // Ép JVM 1.8 và bỏ qua kiểm tra Metadata (Sửa lỗi Incompatible Kotlin)
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
} // Đóng ngoặc subprojects ở ĐÂY
