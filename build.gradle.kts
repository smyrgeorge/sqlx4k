group = "io.github.smyrgeorge"
version = "0.17.0"

plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.multiplatform) apply false
}

repositories {
    mavenCentral()
}

subprojects {
    // Run with ./gradlew :dokkaHtmlMultiModule
    apply(plugin = "org.jetbrains.dokka")
}
