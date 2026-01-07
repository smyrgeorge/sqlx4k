import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
    alias(libs.plugins.ksp) // We need the KSP plugin for code-generation.
}

kotlin {
    sourceSets {
        all {
            languageSettings.enableLanguageFeature("ContextParameters")
        }
        commonMain {
            dependencies {
                implementation(project(":sqlx4k-postgres-pgmq"))
            }
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
    }
}

ksp {
    arg("dialect", "postgresql")
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
    arg("validate-sql-schema", "false")
    arg("schema-migrations-path", "./db/migrations")
}

dependencies {
    add("kspCommonMainMetadata", project(":sqlx4k-codegen"))
    // You can also enable the code generation only for a specific target
    // add("kspMacosArm64", project(":sqlx4k-codegen"))
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
                "MacosX64",
                "LinuxArm64",
                "LinuxX64",
                "MingwX64"
            )

            else -> it.split(",").map { t -> t.trim().capitalized() }
        }
    } ?: listOf(defaultTarget) // Default for local development.
}