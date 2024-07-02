import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.lang.System.getenv

plugins {
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.multiplatform
    kotlin("multiplatform") version "2.0.0"
}

group = "io.github.smyrgeorge"
version = "0.1.0"

repositories {
    mavenCentral()
}

private val os = DefaultNativePlatform.getCurrentOperatingSystem()
private val arch = DefaultNativePlatform.getCurrentArchitecture()

private val exeExt: String
    get() = when {
        os.isWindows -> ".exe"
        else -> ""
    }

private val cargo: String
    get() = when {
        os.isWindows -> getenv("USERPROFILE")
        else -> getenv("HOME")
    }?.let(::File)
        ?.resolve(".cargo/bin/cargo$exeExt")
        ?.takeIf { it.exists() }
        ?.absolutePath
        ?: throw GradleException("Rust cargo binary is required to build project but it wasn't found.")

kotlin {
    fun projectFile(path: String): String =
        projectDir.resolve(path).absolutePath

    applyDefaultHierarchyTemplate()

    val host: Host = when {
        os.isMacOsX && arch.isArm64 -> Host(macosArm64(), "aarch64-apple-darwin")
        os.isMacOsX && arch.isAmd64 -> Host(macosX64(), "x86_64-apple-darwin")
        os.isLinux && arch.isArm64 -> Host(linuxArm64(), "aarch64-unknown-linux-gnu")
        os.isLinux && arch.isAmd64 -> Host(linuxX64(), "x86_64-unknown-linux-gnu")
        os.isWindows && arch.isAmd64 -> Host(mingwX64())
        else -> throw GradleException("OS: $os and architecture: $arch is not supported in script configuration.")
    }

    tasks.create("binaries") {
        dependsOn("${host.target.targetName}Binaries")
        doLast { host.renameBinaries() }
    }

    host.target {
        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val buildRustLib by tasks.creating {
                    exec {
                        executable = cargo
                        args(
                            "build",
                            "--manifest-path", projectFile("rust_lib/Cargo.toml"),
                            "--package", "rust_lib",
                            "--lib",
                            // Had to specify manually here.
                            // When running/building from IntelliJ the arch is amd64 (not arm64),
                            // but cargo runs with arm64.
                            host.rustTarget?.let { "--target=$it" } ?: "",
                            "--release"
                        )
                    }
                }
                tasks.getByName(interopProcessingTaskName) {
                    dependsOn(buildRustLib)
                }
                header(projectFile("rust_lib/target/rust_lib.h"))
            }
        }

        binaries.executable {
            entryPoint = "main"
            baseName = "sqlx4k"
            linkerOpts += projectFile(
                path = when {
                    os.isWindows -> "rust_lib/target/${host.rustTarget}/release/rust_lib.lib"
                    else -> "rust_lib/target/${host.rustTarget}/release/librust_lib.a"
                }
            )
        }
    }
    sourceSets {
        getByName("nativeMain").dependencies {
            // https://github.com/Kotlin/kotlinx.coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
    }
}

class Host(
    val target: KotlinNativeTarget,
    val rustTarget: String? = null
) {

    fun target(configure: KotlinNativeTarget.() -> Unit): Unit =
        target.run(configure)

    fun renameBinaries() {
        project.layout.buildDirectory.get().asFile
            .resolve("bin/${target.name}")
            .walkTopDown().forEach {
                if (it.extension != "kexe") return@forEach
                val renamed = it.parentFile.resolve("${it.nameWithoutExtension}$exeExt")
                it.renameTo(renamed)
            }
    }
}
