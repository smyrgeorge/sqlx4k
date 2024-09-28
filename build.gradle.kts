group = "io.github.smyrgeorge"
version = "0.20.0"

plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.multiplatform) apply false
}

repositories {
    mavenCentral()
}

subprojects {
    // Exclude examples.
    if (projectDir.path.contains("/examples")) return@subprojects
    // Exclude :sqldelight (since it's an empty project).
    if (name == "sqldelight") return@subprojects
    // Run with ./gradlew :dokkaHtmlMultiModule
    apply(plugin = "org.jetbrains.dokka")
}
