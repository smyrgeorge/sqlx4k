plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.lib")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

kotlin {
    @Suppress("unused")
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
            }
        }
    }
}
