package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class MultiplatformExamplesConventions : Plugin<Project> {
    override fun apply(project: Project) {
        val targets = Utils.targetsOf(project)
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.extensions.configure<KotlinMultiplatformExtension> {
            val availableTargets = mapOf(
                Pair("iosArm64") { iosArm64 { binaries { executable() } } },
                Pair("androidNativeArm64") { androidNativeArm64 { binaries { executable() } } },
                Pair("androidNativeX64") { androidNativeX64 { binaries { executable() } } },
                Pair("macosArm64") { macosArm64 { binaries { executable() } } },
                Pair("macosX64") { macosX64 { binaries { executable() } } },
//                Pair("linuxArm64") { linuxArm64 { binaries { executable() } } },
//                Pair("linuxX64") { linuxX64 { binaries { executable() } } },
//                Pair("mingwX64") { mingwX64 { binaries { executable() } } },
            )

            targets.forEach {
                println("Enabling target $it")
                availableTargets[it]?.invoke()
            }

            applyDefaultHierarchyTemplate()
        }
    }
}
