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
                implementation(project(":sqlx4k-postgres"))
                implementation("app.cash.sqldelight:runtime:2.0.2")
//                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
