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
                // Depend only on the runtime library (not the codegen processor module) so this
                // module's KSP classpath never pulls in the real-impl processors. TypeNames is
                // self-contained here on purpose.
                implementation(project(":sqlx4k"))
                implementation(libs.ksp)
                // Parses @Query SQL to derive in-memory WHERE predicates.
                implementation(libs.jsqlparser)
            }
        }
        jvmTest {
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
    // Run this module's in-memory generator on its own tests (the main codegen processors are
    // brought in transitively via the implementation dependency above).
    add("kspJvmTest", project(":sqlx4k-codegen-test"))
}
