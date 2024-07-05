plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    macosArm64 { binaries { executable() } }
//    macosX64 { binaries { executable() } }
//    linuxArm64 { binaries { executable() } }
//    linuxX64 { binaries { executable() } }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k"))
//                // https://github.com/Kotlin/kotlinx.coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
    }
}
