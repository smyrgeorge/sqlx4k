import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform


plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

private val os = DefaultNativePlatform.getCurrentOperatingSystem()
private val arch = DefaultNativePlatform.getCurrentArchitecture()

val chosenTargets = (properties["targets"] as? String)
    ?.let {
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
    val availableTargets = mapOf(
        Pair("iosArm64") { iosArm64() },
        Pair("macosArm64") { macosArm64() },
        Pair("macosX64") { macosX64() },
        Pair("androidNativeX64") { androidNativeX64() },
        Pair("androidNativeArm64") { androidNativeArm64() },
        Pair("linuxArm64") { linuxArm64() },
        Pair("linuxX64") { linuxX64() },
        Pair("mingwX64") { mingwX64() },
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
            }
        }
    }
}

