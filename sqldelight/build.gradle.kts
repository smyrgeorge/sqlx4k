plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    macosArm64()

    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k"))
                api(libs.sqldeligh)
            }
        }
    }
}
