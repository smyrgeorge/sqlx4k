plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.examples")
}

kotlin {
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-sqlite"))
            }
        }
    }
}
