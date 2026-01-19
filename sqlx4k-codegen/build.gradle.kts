plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.jvm")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
    alias(libs.plugins.ksp)
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        jvmMain {
            dependencies {
                implementation(project(":sqlx4k"))
                implementation(project(":sqlx4k-arrow"))
                implementation(libs.ksp)
                implementation(libs.jsqlparser)
                implementation(libs.calcite.core)
                implementation(libs.log4k.slf4j)
            }
        }
        jvmTest {
            languageSettings.enableLanguageFeature("ContextParameters")
            dependencies {
                implementation(project(":sqlx4k"))
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

ksp {
    arg("output-package", "io.github.smyrgeorge.sqlx4k.processor.test.generated")
}

dependencies {
    // Use the codegen processor from this project for jvmTest
    add("kspJvmTest", project(":sqlx4k-codegen"))
}
