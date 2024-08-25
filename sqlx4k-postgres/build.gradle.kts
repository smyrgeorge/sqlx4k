import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.lang.System.getenv

plugins {
    kotlin("multiplatform")
    // https://github.com/vanniktech/gradle-maven-publish-plugin
    id("com.vanniktech.maven.publish") version "0.28.0"
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

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

val chosenTargets = (properties["targets"] as? String)?.split(",")
    ?: listOf("macosArm64") // For local development.

kotlin {
    fun KotlinNativeTarget.rust(target: String, useCross: Boolean = false) {
        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val cargo = tasks.create("cargo-$target") {
                    if (useCross) {
                        exec { commandLine("cargo", "install", "cross", "--git", "https://github.com/cross-rs/cross") }
                    }

                    exec {
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

                tasks.getByName(interopProcessingTaskName) {
                    dependsOn(cargo)
                }

                definitionFile.set(projectDir.resolve("src/nativeInterop/cinterop/$target.def"))
            }
        }
    }

    val availableTargets = mapOf(
        Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios", !os.isMacOsX) } },
        Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin", !os.isMacOsX) } },
        Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin", !os.isMacOsX) } },
        Pair("androidNativeX64") { androidNativeX64 { rust("aarch64-linux-android", true) } },
        Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu", !os.isLinux || !arch.isArm64) } },
        Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu", !os.isLinux) } },
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
        name = "sqlx4k-postgres"
        description = "A non-blocking PostgreSQL database driver written in Kotlin for the Native platform."
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
