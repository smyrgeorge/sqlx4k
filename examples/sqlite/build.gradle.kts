import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
    alias(libs.plugins.sqlx4k)
}

kotlin {
    // These examples link BOTH sqlx4k-sqlite and sqlx4k-sqlite-cipher into a single native binary to
    // benchmark them side by side. Each Rust staticlib bundles its own copy of libstd, so GNU-style
    // linkers (Android NDK, Linux, MinGW) abort on duplicate libstd symbols (rust_eh_personality,
    // std::panicking::EMPTY_PANIC, …). They are identical, so let the linker keep the first. macOS/iOS
    // use ld64, which both tolerates the duplicates and rejects this flag — hence it is set per-family.
    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.ANDROID || konanTarget.family == Family.LINUX || konanTarget.family == Family.MINGW) {
            binaries.configureEach {
                linkerOpts("-Wl,--allow-multiple-definition")
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // The plugin adds sqlx4k-sqlite (the driver); the cipher driver is linked in as well.
                implementation(project(":sqlx4k-sqlite-cipher"))
                implementation(libs.kotlinx.io.core) // For resetting the demo db files between runs.
            }
        }
    }
}

sqlx4k {
    driver = SQLite
    generatedCodePackage = "io.github.smyrgeorge.sqlx4k.examples.sqlite"
    args = mapOf(
        "validate-sql-schema" to "false",
        "schema-migrations-path" to "./db/migrations",
    )
}
