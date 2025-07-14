plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.rust")
    id("io.github.smyrgeorge.sqlx4k.publish")
}

kotlin {
    @Suppress("unused")
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val commonMain by getting {
            dependencies {
                api(project(":sqlx4k"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
                implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.10.2")
            }
        }
    }
}
