plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.simple")
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-mysql"))
            }
        }
    }
}
