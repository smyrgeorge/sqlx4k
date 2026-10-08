group = "io.github.smyrgeorge"
version = libs.versions.sqlx4k.get()

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
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
