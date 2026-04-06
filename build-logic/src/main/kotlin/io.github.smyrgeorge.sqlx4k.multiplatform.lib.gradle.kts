import io.github.smyrgeorge.sqlx4k.multiplatform.Utils
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.nativeplatform.platform.internal.ArchitectureInternal
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.lang.System.getenv

val os: DefaultOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()
val arch: ArchitectureInternal = DefaultNativePlatform.getCurrentArchitecture()

/**
 * Represents the file extension used for executable files.
 */
val exeExt: String = when {
    os.isWindows -> ".exe"
    else -> ""
}

/**
 * Represents the path to the Rust cargo binary used to build the project.
 */
val cargo: String = when {
    os.isWindows -> getenv("USERPROFILE")
    else -> getenv("HOME")
}?.let(::File)
    ?.resolve(".cargo/bin/cargo$exeExt")?.absolutePath
    ?: throw GradleException("Rust cargo binary is required to build project but it wasn't found.")

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

extensions.configure<KotlinMultiplatformExtension> {
    val targets = Utils.targetsOf(project)
    val availableTargets = mapOf(
        Pair("jvm") {
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        },
        Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios") } },
        Pair("iosSimulatorArm64") { iosSimulatorArm64 { rust("aarch64-apple-ios-sim") } },
        Pair("androidNativeArm64") { androidNativeArm64 { rust("aarch64-linux-android") } },
        Pair("androidNativeX64") { androidNativeX64 { rust("x86_64-linux-android") } },
        Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin") } },
        Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu") } },
        Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu") } },
        Pair("mingwX64") { mingwX64 { rust("x86_64-pc-windows-gnu") } },
    )

    targets.forEach {
        println("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()
}

/**
 * Configures Rust integration for the given Kotlin Native Target.
 *
 * @param target Specifies the target triple for the Rust compilation (e.g., "x86_64-pc-windows-gnu").
 */
fun KotlinNativeTarget.rust(target: String) {
    val tasks = project.tasks
    fun file(path: String) = project.projectDir.resolve(path)

    compilations["main"].cinterops {
        create("ffi") {

            if (target == "x86_64-pc-windows-gnu") {
                definitionFile.set(file("src/nativeInterop/cinterop/sqlx4k-mingwX64.def"))
            } else {
                definitionFile.set(file("src/nativeInterop/cinterop/sqlx4k.def"))
            }

            val cargoTask = tasks.register("cargo-$target") {
                val exec = project.serviceOf<ExecOperations>()
                doLast {
                    exec.exec {
                        executable = cargo
                        args(
                            "build",
                            "--manifest-path", file("src/rust/Cargo.toml").absolutePath,
                            "--target=$target",
                            "--release"
                        )
                    }
                }
            }
            tasks.getByName(interopProcessingTaskName) { dependsOn(cargoTask) }
        }
    }
}
