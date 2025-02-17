plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
}

kotlin {
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-mysql"))
            }
        }
    }
}
