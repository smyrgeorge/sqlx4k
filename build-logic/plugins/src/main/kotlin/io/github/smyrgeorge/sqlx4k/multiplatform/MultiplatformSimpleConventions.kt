package io.github.smyrgeorge.sqlx4k.multiplatform

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class MultiplatformSimpleConventions : Plugin<Project> {
    override fun apply(project: Project) {
        val targets = Utils.targetsOf(project)
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.extensions.configure<KotlinMultiplatformExtension> {
            val availableTargets = mapOf(
                Pair("iosArm64") { iosArm64() },
                Pair("androidNativeArm64") { androidNativeArm64() },
                Pair("androidNativeX64") { androidNativeX64() },
                Pair("macosArm64") { macosArm64() },
                Pair("macosX64") { macosX64() },
                Pair("linuxArm64") { linuxArm64() },
                Pair("linuxX64") { linuxX64() },
                Pair("mingwX64") { mingwX64() },
            )

            targets.forEach {
                println("Enabling target $it")
                availableTargets[it]?.invoke()
            }

            applyDefaultHierarchyTemplate()
        }
    }
}
