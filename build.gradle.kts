group = "io.github.smyrgeorge"
version = "1.5.6"

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.pubhish) apply false
    alias(libs.plugins.dokka) apply false
}

repositories {
    mavenCentral()
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}
