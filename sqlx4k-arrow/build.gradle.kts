plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                api(project(":sqlx4k"))
                api(libs.arrow.core)
            }
        }
    }
}
