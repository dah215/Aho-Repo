import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Không áp dụng plugin Android ở root
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    // Chỉ áp dụng cho module Android thực sự (có src/main)
    if (file("${project.projectDir}/src/main").exists()) {

        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.android")
        apply(plugin = "com.lagradost.cloudstream3.gradle")

        extensions.configure<LibraryExtension> {
            compileSdk = 34

            defaultConfig {
                minSdk = 21
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(
                    org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
                )
                freeCompilerArgs.add("-Xskip-metadata-version-check")
            }
        }

        afterEvaluate {
            extensions.findByName("cloudstream")?.let {
                val ext =
                    it as com.lagradost.cloudstream3.gradle.CloudstreamExtension
                ext.setRepo(
                    System.getenv("GITHUB_REPOSITORY")
                        ?: "https://github.com/dah215/Aho-Repo"
                )
                ext.authors = listOf("CloudStream Builder")
            }
        }
    }
}
