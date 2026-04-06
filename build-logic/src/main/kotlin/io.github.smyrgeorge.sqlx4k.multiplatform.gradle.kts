import io.github.smyrgeorge.sqlx4k.multiplatform.Utils
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val os = DefaultNativePlatform.getCurrentOperatingSystem()
val arch = DefaultNativePlatform.getCurrentArchitecture()

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

extensions.configure<KotlinMultiplatformExtension> {
    val targets = Utils.targetsOf(project)
    val availableTargets = listOfNotNull(
        Pair("jvm") {
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        },
        Pair("iosArm64") { iosArm64() },
        Pair("iosSimulatorArm64") { iosSimulatorArm64() },
        Pair("androidNativeArm64") { androidNativeArm64() },
        Pair("androidNativeX64") { androidNativeX64() },
        Pair("macosArm64") { macosArm64() },
        Pair("linuxArm64") { linuxArm64() },
        Pair("linuxX64") { linuxX64() },
        Pair("mingwX64") { mingwX64() },
    ).toMap()

    targets.forEach {
        println("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()
}
