import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.lang.System.getenv

plugins {
    // https://plugins.gradle.org/plugin/org.jetbrains.kotlin.multiplatform
    kotlin("multiplatform") version "2.0.0"
    // https://github.com/vanniktech/gradle-maven-publish-plugin
    id("com.vanniktech.maven.publish") version "0.28.0"
}

group = "io.github.smyrgeorge"
version = "0.1.0"

repositories {
    mavenCentral()
}

private val os = DefaultNativePlatform.getCurrentOperatingSystem()
private val arch = DefaultNativePlatform.getCurrentArchitecture()

fun projectFile(path: String): String =
    projectDir.resolve(path).absolutePath

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

val chosenTargets = (properties["targets"] as? String)?.split(",")
    ?: listOf("macosArm64", "macosX64", "linuxArm64", "linuxX64")

kotlin {
    fun KotlinNativeTarget.rust(target: String) {
        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val cargo = tasks.create("cargo-$target") {
                    exec {
                        executable = cargo
                        args(
                            "build",
                            "--manifest-path", projectFile("rust_lib/Cargo.toml"),
                            "--package", "rust_lib",
                            "--lib",
                            "--target=$target",
                            "--release"
                        )
                    }
                }

                tasks.getByName(interopProcessingTaskName) {
                    dependsOn(cargo)
                }

                definitionFile.set(projectDir.resolve("src/nativeInterop/cinterop/$target.def"))
            }
        }

        // TODO: remove when we start building proper tests.
        binaries.executable {
            entryPoint = "main"
            baseName = "sqlx4k"
            linkerOpts += projectFile(
                path = when {
                    os.isWindows -> "rust_lib/target/$target/release/rust_lib.lib"
                    else -> "rust_lib/target/$target/release/librust_lib.a"
                }
            )
        }

    }

    val availableTargets = mapOf(
        Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin") } },
        Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin") } },
        Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu") } },
        Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu") } },
    )
    chosenTargets.forEach {
        println("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val nativeMain by getting {
            dependencies {
                // https://github.com/Kotlin/kotlinx.coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = group as String,
        artifactId = name,
        version = version as String
    )

    pom {
        name = "sqlx4k"
        description = "A small non-blocking database driver written in Kotlin for the Native platform."
        url = "https://github.com/smyrgeorge/sqlx4k"

        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/smyrgeorge/sqlx4k/blob/main/LICENSE"
            }
        }

        developers {
            developer {
                id = "smyrgeorge"
                name = "Yorgos S."
                email = "smyrgoerge@gmail.com"
                url = "https://smyrgeorge.github.io/"
            }
        }

        scm {
            url = "https://github.com/smyrgeorge/sqlx4k"
            connection = "scm:git:https://github.com/smyrgeorge/sqlx4k.git"
            developerConnection = "scm:git:git@github.com:smyrgeorge/sqlx4k.git"
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Enable GPG signing for all publications
    signAllPublications()
}
