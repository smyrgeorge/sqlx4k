group = "io.github.smyrgeorge"
version = "1.6.0"

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.pubhish) apply false
    alias(libs.plugins.dokka) apply false
}

repositories {
    google()
    mavenCentral()
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        google()
        mavenCentral()
    }
}
