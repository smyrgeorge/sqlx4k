plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.rust")
    id("io.github.smyrgeorge.sqlx4k.publish")
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        @Suppress("unused")
        val nativeMain by getting {
            dependencies {
                api(project(":sqlx4k"))
            }
        }
    }
}
