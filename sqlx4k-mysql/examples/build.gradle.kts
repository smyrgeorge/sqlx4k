import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

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
//                "mingwX64"
            )

            else -> it.split(",").map { t -> t.trim() }
        }
    } ?: listOf("macosArm64") // For local development.

kotlin {
    val targets = project.targets()
    val availableTargets: Map<String, () -> KotlinNativeTarget> = mapOf(
        Pair("iosArm64") { iosArm64 { binaries { executable() } } },
        Pair("androidNativeX64") { androidNativeX64 { binaries { executable() } } },
        Pair("androidNativeArm64") { androidNativeArm64 { binaries { executable() } } },
        Pair("macosArm64") { macosArm64 { binaries { executable() } } },
        Pair("macosX64") { macosX64 { binaries { executable() } } },
        Pair("linuxArm64") { linuxArm64 { binaries { executable() } } },
        Pair("linuxX64") { linuxX64 { binaries { executable() } } },
        Pair("mingwX64") { mingwX64 { binaries { executable() } } },
    )

    targets.forEach {
        println("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-mysql"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
