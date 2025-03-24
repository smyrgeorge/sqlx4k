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
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        @Suppress("unused")
        val nativeTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
            }
        }
    }
}
