import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform")
//    alias(libs.plugins.kotlin.multiplatform)
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
        val nativeMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
