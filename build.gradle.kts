import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.gradle.BaseExtension // THÊM DÒNG NÀY

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

    // ÉP CẤU HÌNH ANDROID CHO TẤT CẢ MODULE CON
    extensions.configure<BaseExtension> {
        compileSdkVersion(34)
        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }
        
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    afterEvaluate {
        extensions.configure<com.lagradost.cloudstream3.gradle.CloudstreamExtension> {
            setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dah215/Aho-Repo")
            authors = listOf("CloudStream Builder")
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}
