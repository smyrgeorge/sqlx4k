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
    ?: listOf("iosArm64", "macosArm64", "macosX64")
//    ?: listOf("iosArm64", "androidNativeX64", "macosArm64", "macosX64", "linuxArm64", "linuxX64")

kotlin {
    fun KotlinNativeTarget.rust(target: String) {
        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val cargo = tasks.create("cargo-$target") {
                    exec {
                        executable = cargo
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
        Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios") } },
        Pair("androidNativeX64") { androidNativeX64 { rust("aarch64-linux-android") } },
        Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin") } },
        Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu") } },
        Pair("macosX64") { macosX64 { rust("x86_64-apple-darwin") } },
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
        name = "sqlx4k-sqlite"
        description = "A non-blocking SQLite database driver written in Kotlin for the Native platform."
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
