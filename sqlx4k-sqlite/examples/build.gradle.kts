plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    macosArm64 { binaries { executable() } }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-sqlite"))
            }
        }
    }
}
