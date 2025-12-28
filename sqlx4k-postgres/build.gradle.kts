plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.lib")
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
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
                implementation(libs.kotlinx.io.core)
                implementation(libs.arrow.core)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.r2dbc.postgresql)
                implementation(libs.r2dbc.pool)
                implementation(libs.kotlinx.coroutines.reactive)
            }
        }
    }
}
