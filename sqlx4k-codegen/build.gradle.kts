plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.jvm")
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ksp)
            }
        }
    }
}
