plugins {
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.multiplatform.jvm")
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ksp)
            }
        }
    }
}
