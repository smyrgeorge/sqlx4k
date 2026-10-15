plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.lib")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
    alias(libs.plugins.android)
}

kotlin {
    compilerOptions {
        // The tests run under Robolectric on Android through an expect/actual base class.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "io.github.smyrgeorge.sqlx4k.sqlite"
        compileSdk = 36
        minSdk = 26
        withHostTestBuilder {}
    }
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
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.sqlite.jdbc)
            }
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.core.ktx)
            }
        }
    }
}
