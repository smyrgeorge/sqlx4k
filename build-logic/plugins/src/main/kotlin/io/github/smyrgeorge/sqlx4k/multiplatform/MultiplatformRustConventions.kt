package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.lang.System.getenv

class MultiplatformRustConventions : Plugin<Project> {
    private val os = DefaultNativePlatform.getCurrentOperatingSystem()
    private val arch = DefaultNativePlatform.getCurrentArchitecture()

    /**
     * A string representation of the operating system.
     *
     * Determined by checking the `os` object's properties to identify the current OS.
     * Possible values are:
     * - "linux" for Linux-based OS
     * - "macos" for macOS
     * - "mingw" for Windows
     *
     * Throws a `GradleException` if the operating system is unsupported.
     */
    private val osString = when {
        os.isLinux -> "linux"
        os.isMacOsX -> "macos"
        os.isWindows -> "mingw"
        else -> throw GradleException("Unsupported operating system: $os")
    }

    /**
     * Represents the architecture string corresponding to the current system architecture.
     *
     * This variable evaluates the architecture type and returns a string indicating whether the system architecture
     * is Arm64 or X64. If the architecture is unsupported, a `GradleException` is thrown.
     *
     * @throws GradleException if the architecture is unsupported.
     */
    private val archString = when {
        arch.isArm64 -> "Arm64"
        arch.isAmd64 -> "X64"
        else -> throw GradleException("Unsupported architecture: $arch")
    }

    /**
     * This variable holds the concatenated string of the operating system identifier and
     * architecture identifier for the target platform.
     */
    private val defaultTarget = "$osString$archString"

    /**
     * Represents the file extension used for executable files.
     *
     * The value of this property is determined based on the operating system. If the operating
     * system is Windows, the file extension is ".exe". For all other operating systems, the
     * file extension is an empty string.
     */
    private val exeExt: String = when {
        os.isWindows -> ".exe"
        else -> ""
    }

    /**
     * Represents the path to the Rust cargo binary used to build the project.
     *
     * The value of this variable is determined based on the operating system.
     * On Windows, it is obtained from the 'USERPROFILE' environment variable.
     * On other operating systems, it is obtained from the 'HOME' environment variable.
     *
     * The value is then passed to the `File` constructor and resolved to the path of the cargo binary
     * located at ".cargo/bin/cargo", with the appropriate file extension based on the operating system.
     *
     * If the file exists, its absolute path is assigned to the cargo variable.
     * If the file does not exist, a GradleException is thrown indicating that the Rust cargo binary is required,
     * but it was not found.
     *
     * @see MultiplatformRustConventions
     * @see MultiplatformRustConventions.os
     * @see MultiplatformRustConventions.exeExt
     *
     * @throws GradleException if the Rust cargo binary is not found.
     */
    private val cargo: String = when {
        os.isWindows -> getenv("USERPROFILE")
        else -> getenv("HOME")
    }?.let(::File)
        ?.resolve(".cargo/bin/cargo$exeExt")?.absolutePath
        ?: throw GradleException("Rust cargo binary is required to build project but it wasn't found.")

    private val cross: String = when {
        os.isWindows -> getenv("USERPROFILE")
        else -> getenv("HOME")
    }?.let(::File)
        ?.resolve(".cargo/bin/cross$exeExt")?.absolutePath
        ?: throw GradleException("Rust cross binary is required to build project but it wasn't found.")

    override fun apply(project: Project) {
        val targets = project.targets()
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.extensions.configure<KotlinMultiplatformExtension> {
            val availableTargets = mapOf(
                Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios", !os.isMacOsX) } },
                Pair("androidNativeArm64") { androidNativeArm64 { rust("aarch64-linux-android", true) } },
                Pair("androidNativeX64") { androidNativeX64 { rust("x86_64-linux-android", true) } },
                Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin", !os.isMacOsX) } },
                Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin", !os.isMacOsX) } },
                Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu", !os.isLinux || !arch.isArm64) } },
                Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu", !os.isLinux) } },
                Pair("mingwX64") { mingwX64 { rust("x86_64-pc-windows-gnu", true) } },
            )

            targets.forEach {
                println("Enabling target $it")
                availableTargets[it]?.invoke()
            }

            applyDefaultHierarchyTemplate()
        }
    }

    /**
     * Returns a list of target strings for the project.
     *
     * If the "targets" property is present and its value is "all", the method returns a fixed list
     * of targets. Otherwise, it splits the value of the "targets" property by commas and trims each
     * target string before returning the list.
     *
     * If the "targets" property is not present,
     * the method returns a default list containing only the target "macosArm64".
     *
     * @return a list of target strings for the project
     */
    private fun Project.targets(): List<String> =
        (properties["targets"] as? String)?.let {
            when (it) {
                "all" -> listOf(
                    "iosArm64",
                    "androidNativeX64",
                    "androidNativeArm64",
                    "macosArm64",
                    "macosX64",
                    "linuxArm64",
                    "linuxX64",
                    "mingwX64"
                )

                else -> it.split(",").map { t -> t.trim() }
            }
        } ?: listOf(defaultTarget) // Default for local development.

    /**
     * Configures Rust integration for the given Kotlin Native Target.
     *
     * @param target Specifies the target triple for the Rust compilation (e.g., "x86_64-pc-windows-gnu").
     * @param useCross Indicates whether to use the cross-compilation tool (default is false).
     */
    private fun KotlinNativeTarget.rust(target: String, useCross: Boolean = false) {
        val tasks = project.tasks
        fun file(path: String) = project.projectDir.resolve(path)

        compilations["main"].cinterops {
            create("ffi") {

                if (project.name == "sqlx4k") {
                    definitionFile.set(file("src/nativeInterop/cinterop/sqlx4k.def"))
                    return@create
                }

                if (target == "x86_64-pc-windows-gnu") {
                    definitionFile.set(file("src/nativeInterop/cinterop/sqlx4k-mingwX64.def"))
                } else {
                    definitionFile.set(file("src/nativeInterop/cinterop/sqlx4k.def"))
                }

                val cargo = tasks.create("cargo-$target") {
                    val exec = project.serviceOf<ExecOperations>()
                    doLast {
                        exec.exec {
                            executable = if (useCross) cross else cargo
                            args(
                                "build",
                                "--manifest-path", file("rust_lib/Cargo.toml").absolutePath,
                                "--target=$target",
                                "--release"
                            )
                        }
                    }
                }
                tasks.getByName(interopProcessingTaskName) { dependsOn(cargo) }
            }
        }
    }
}
