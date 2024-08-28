package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.get
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.lang.System.getenv

class MultiplatformConventions : Plugin<Project> {
    private val os = DefaultNativePlatform.getCurrentOperatingSystem()
    private val arch = DefaultNativePlatform.getCurrentArchitecture()

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
     * @see MultiplatformConventions
     * @see MultiplatformConventions.os
     * @see MultiplatformConventions.exeExt
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
        ?: throw GradleException("Rust cargo binary is required to build project but it wasn't found.")


    override fun apply(project: Project) {

        val chosenTargets = (project.properties["targets"] as? String)?.let {
            when (it) {
                "all" -> listOf(
                    "iosArm64",
                    "androidNativeX64",
                    "androidNativeArm64",
                    "macosArm64",
                    "macosX64",
                    "linuxArm64",
                    "linuxX64",
//                "mingwX64"
                )

                else -> it.split(",").map { t -> t.trim() }
            }
        } ?: listOf("macosArm64") // For local development.

        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        (project.kotlinExtension as KotlinMultiplatformExtension).apply {
            val availableTargets = mapOf(
                Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios", !os.isMacOsX) } },
                Pair("androidNativeX64") { androidNativeX64 { rust("x86_64-linux-android", true) } },
                Pair("androidNativeArm64") { androidNativeArm64 { rust("aarch64-linux-android", true) } },
                Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin", !os.isMacOsX) } },
                Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin", !os.isMacOsX) } },
                Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu", !os.isLinux || !arch.isArm64) } },
                Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu", !os.isLinux) } },
                Pair("mingwX64") { mingwX64 { rust("x86_64-pc-windows-gnu", true) } },
            )

            chosenTargets.forEach {
                println("Enabling target $it")
                availableTargets[it]?.invoke()
            }

            applyDefaultHierarchyTemplate()
        }
    }

    private fun KotlinNativeTarget.rust(target: String, useCross: Boolean = false) {
        val tasks = project.tasks
        val projectDir = project.projectDir

        compilations["main"].cinterops {
            create("librust_lib") {
                val cargo = tasks.create("cargo-$target") {
                    if (useCross) {
                        project.exec {
                            executable = cargo
                            args(
                                "install",
                                "cross",
                                "--git", "https://github.com/cross-rs/cross"
                            )
                        }
                    }

                    project.exec {
                        executable = if (useCross) cross else cargo
                        args(
                            "build",
                            "--manifest-path", projectDir.resolve("rust_lib/Cargo.toml").absolutePath,
                            "--target=$target",
                            "--release"
                        )
                    }
                }

                tasks.getByName(interopProcessingTaskName) { dependsOn(cargo) }
                definitionFile.set(projectDir.resolve("src/nativeInterop/cinterop/$target.def"))
            }
        }
    }
}
