package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

object Utils {
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
    fun targetsOf(project: Project): List<String> =
        (project.properties["targets"] as? String)?.let {
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
}
