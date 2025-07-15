plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.lib")
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
                implementation(libs.r2dbc.postgresql)
                implementation(libs.r2dbc.pool)
                implementation(libs.kotlinx.coroutines.reactive)
            }
        }
    }
}
