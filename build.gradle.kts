import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
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

fun projectFile(path: String): String =
    projectDir.resolve(path).absolutePath

private val rustLibAbsolutePath: String
    get() = projectFile(
        path = when {
            os.isWindows -> "rust_lib/target/release/rust_lib.lib"
            else -> "rust_lib/target/release/librust_lib.a"
        }
    )

kotlin {
    applyDefaultHierarchyTemplate()

    val host: Host = when {
//        os.isMacOsX && arch.isAmd64 -> Host(macosX64())
//        os.isMacOsX && arch.isArm64 -> Host(macosArm64())
        os.isMacOsX -> Host(macosArm64())
        os.isLinux && arch.isAmd64 -> Host(linuxX64())
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
            linkerOpts += rustLibAbsolutePath
        }
    }
    sourceSets {
        getByName("nativeMain").dependencies {
            // Add more dependencies here (if needed).
        }
    }
}

class Host(
    val target: KotlinNativeTargetWithHostTests,
) {

    fun target(configure: KotlinNativeTargetWithHostTests.() -> Unit): Unit =
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
