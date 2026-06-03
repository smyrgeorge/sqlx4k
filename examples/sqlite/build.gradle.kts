import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
    alias(libs.plugins.ksp) // We need the KSP plugin for code-generation.
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
                implementation(project(":sqlx4k-sqlite"))
                implementation(project(":sqlx4k-sqlite-cipher"))
                implementation(libs.kotlinx.io.core) // For resetting the demo db files between runs.
            }
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
    }
}

ksp {
    arg("dialect", "sqlite")
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.sqlite")
    arg("validate-sql-schema", "false")
    arg("schema-migrations-path", "./db/migrations")
}

dependencies {
    add("kspCommonMainMetadata", project(":sqlx4k-codegen"))
}

targetsOf(project).forEach {
    project.tasks.getByName("compileKotlin$it") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks.withType<KotlinCompilationTask<*>> {
    dependsOn("kspCommonMainKotlinMetadata")
}

fun targetsOf(project: Project): List<String> {
    val os = DefaultNativePlatform.getCurrentOperatingSystem()
    val arch = DefaultNativePlatform.getCurrentArchitecture()

    val osString = when {
        os.isLinux -> "Linux"
        os.isMacOsX -> "Macos"
        os.isWindows -> "Mingw"
        else -> throw GradleException("Unsupported operating system: $os")
    }
    val archString = when {
        arch.isArm64 -> "Arm64"
        arch.isAmd64 -> "X64"
        else -> throw GradleException("Unsupported architecture: $arch")
    }
    val defaultTarget = "$osString$archString"
    return (project.properties["targets"] as? String)?.let {
        when (it) {
            "all" -> listOf(
                "IosArm64",
                "AndroidNativeX64",
                "AndroidNativeArm64",
                "MacosArm64",
                "LinuxArm64",
                "LinuxX64",
                "MingwX64"
            )

            else -> it.split(",").map { t -> t.trim().capitalized() }
        }
    } ?: listOf(defaultTarget) // Default for local development.
}
