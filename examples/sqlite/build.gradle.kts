plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
}

kotlin {
    sourceSets {
        @Suppress("unused")
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-sqlite"))
            }
        }
    }
}
