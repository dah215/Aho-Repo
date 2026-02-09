plugins {
    // declare plugins with versions but don't apply at root
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

subprojects {
    // Không khai báo repositories ở đây (đã chuyển vào settings.gradle.kts)
    // Áp dụng plugin Android chỉ cho những module có mã nguồn Android
    if (file("${project.projectDir}/src/main").exists()) {
        apply(plugin = "com.android.library")
        apply(plugin = "org.jetbrains.kotlin.android")
        // plugin tuỳ chỉnh: cố gắng apply nếu có trong classpath, không làm sập script nếu không có
        try {
            apply(plugin = "com.lagradost.cloudstream3.gradle")
        } catch (_: Throwable) {
            // plugin không có -> bỏ qua
        }
    }
}

/*
  Tạo task "make" để tương thích với CI/command gọi "gradle make".
  Task này sẽ kích hoạt assemble cho tất cả subprojects có task 'assemble'.
*/
tasks.register("make") {
    group = "build"
    description = "Compatibility task: assemble all Android modules (alias for CI 'make')"
    dependsOn(subprojects.flatMap { sp ->
        sp.tasks.matching { it.name == "assemble" }.toList()
    })
}
