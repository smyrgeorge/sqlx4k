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

@Suppress("unused")
class MultiplatformLibConventions : Plugin<Project> {
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
     * @see MultiplatformLibConventions
     * @see MultiplatformLibConventions.os
     * @see MultiplatformLibConventions.exeExt
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
        val targets = Utils.targetsOf(project)
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

            println("Enabling target jvm")
            jvm()

            targets.forEach {
                println("Enabling target $it")
                availableTargets[it]?.invoke()
            }

            applyDefaultHierarchyTemplate()
        }
    }

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

                val cargo = tasks.register("cargo-$target") {
                    val exec = project.serviceOf<ExecOperations>()
                    doLast {
                        exec.exec {
                            @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
                            executable = if (CROSS_ENABLED && useCross) cross else cargo
                            args(
                                "build",
                                "--manifest-path", file("src/rust/Cargo.toml").absolutePath,
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

    companion object {
        private const val CROSS_ENABLED: Boolean = false
    }
}
