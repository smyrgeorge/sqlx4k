package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.lang.System.getenv

class MultiplatformConventions : Plugin<Project> {

    private val os = DefaultNativePlatform.getCurrentOperatingSystem()
    private val arch = DefaultNativePlatform.getCurrentArchitecture()

    private val exeExt: String = when {
        os.isWindows -> ".exe"
        else -> ""
    }

    private val cargo: String = when {
        os.isWindows -> getenv("USERPROFILE")
        else -> getenv("HOME")
    }?.let(::File)
        ?.resolve(".cargo/bin/cargo$exeExt")
        ?.takeIf { it.exists() }
        ?.absolutePath
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
                Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin", !os.isMacOsX) } },
                Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin", !os.isMacOsX) } },
                Pair("androidNativeX64") { androidNativeX64 { rust("x86_64-linux-android", true) } },
                Pair("androidNativeArm64") { androidNativeArm64 { rust("aarch64-linux-android", true) } },
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

        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val cargo = tasks.create("cargo-$target") {
                    if (useCross) {
                        project.exec {
                            commandLine(
                                "cargo",
                                "install",
                                "cross",
                                "--git",
                                "https://github.com/cross-rs/cross"
                            )
                        }
                    }

                    project.exec {
                        executable = if (useCross) "cross" else cargo
                        args(
                            "build",
                            "--manifest-path", projectDir.resolve("rust_lib/Cargo.toml").absolutePath,
                            "--package", "rust_lib",
                            "--lib",
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
