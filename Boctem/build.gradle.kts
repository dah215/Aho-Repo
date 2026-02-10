plugins {
    id("com.android.library")
    kotlin("android")
    // Adds the Cloudstream Gradle plugin
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.boctem"
    compileSdk = 34 // Updated to standard version

    defaultConfig {
        minSdk = 21
        // targetSdk is deprecated in library modules, but if needed, keep it here or remove it.
        // Usually handled by the main app, but for extensions, we define compatibility.
        lint.targetSdk = 34 
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// IMPORTANT: This block tells Gradle where to download the missing libraries
repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io") 
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib"))

    // Fixes 'Unresolved reference: CloudstreamPlugin'
    // Provides the core Cloudstream classes
    compileOnly("com.github.recloudstream:cloudstream:pre-release")

    // Fixes 'Cannot access class com.lagradost.nicehttp.Requests'
    implementation("com.github.Lagradost:NiceHttp:0.4.1")

    // Required for HTML parsing (Standard in almost all extensions)
    implementation("org.jsoup:jsoup:1.17.2")
}
