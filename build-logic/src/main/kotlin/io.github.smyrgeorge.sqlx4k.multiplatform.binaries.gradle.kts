import io.github.smyrgeorge.sqlx4k.multiplatform.Utils
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

extensions.configure<KotlinMultiplatformExtension> {
    val targets = Utils.targetsOf(project)
    val availableTargets = mapOf(
        Pair("jvm") {
            jvm {
                @OptIn(ExperimentalKotlinGradlePluginApi::class)
                mainRun {
                    mainClass.set("MainKt")
                }
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        },
        Pair("iosX64") { iosX64 { binaries { executable() } } },
        Pair("iosArm64") { iosArm64 { binaries { executable() } } },
        Pair("iosSimulatorArm64") { iosSimulatorArm64 { binaries { executable() } } },
        Pair("androidNativeArm64") { androidNativeArm64 { binaries { executable() } } },
        Pair("androidNativeX64") { androidNativeX64 { binaries { executable() } } },
        Pair("macosArm64") { macosArm64 { binaries { executable() } } },
        Pair("linuxArm64") { linuxArm64 { binaries { executable() } } },
        Pair("linuxX64") { linuxX64 { binaries { executable() } } },
        Pair("mingwX64") { mingwX64 { binaries { executable() } } },
    )

    targets.forEach {
        println("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()
}
