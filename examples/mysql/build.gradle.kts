plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.examples")
}

group = rootProject.group
version = rootProject.version

kotlin {
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-mysql"))
            }
        }
    }
}
