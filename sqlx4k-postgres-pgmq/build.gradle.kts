plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

kotlin {
    sourceSets {
        all {
            languageSettings.enableLanguageFeature("ContextParameters")
        }
        configureEach {
            languageSettings.progressiveMode = true
        }
        commonMain {
            dependencies {
                api(project(":sqlx4k-postgres"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.r2dbc.postgresql)
                implementation(libs.r2dbc.pool)
            }
        }
    }
}
