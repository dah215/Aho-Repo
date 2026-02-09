import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // Declared with apply false so subprojects can apply them without repeating versions
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
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
    // giữ repositories cho mọi subproject
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    // chỉ áp dụng plugin cho project có mã nguồn Android
    if (file("${project.projectDir}/src/main").exists()) {
        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.android")
        // chỉ apply plugin tuỳ chỉnh nếu plugin đó tồn tại trong classpath / pluginManagement
        try {
            apply(plugin = "com.lagradost.cloudstream3.gradle")
        } catch (_: Exception) {
            // nếu plugin không có sẵn thì bỏ qua (không gây lỗi biên dịch script)
        }
    }
}
