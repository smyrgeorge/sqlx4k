import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    id("io.github.smyrgeorge.sqlx4k.publish")
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
    }
}

java {
    // Gradle plugins run on the consumer's Gradle daemon JVM; sqlx4k targets JVM 21 everywhere.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // Provided by the consumer's Kotlin Gradle plugin at runtime — must NOT be bundled.
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.gradle.kotlin.plugin)

    // Applied on the consumer's behalf — a real dependency (not compileOnly), so applying
    // `io.github.smyrgeorge.sqlx4k` puts KSP on the consumer's buildscript classpath.
    implementation(libs.gradle.ksp.plugin)

    testImplementation(libs.kotlin.gradle.plugin.api)
    testImplementation(libs.gradle.kotlin.plugin)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("sqlx4k") {
            id = "io.github.smyrgeorge.sqlx4k"
            implementationClass = "io.github.smyrgeorge.sqlx4k.gradle.Sqlx4kGradlePlugin"
            displayName = "sqlx4k Gradle plugin"
            description =
                "Wires the sqlx4k code generator (KSP) and the sqlx4k driver into a Kotlin project from a single `sqlx4k { }` block."
        }
    }
}

// Generate a BuildConfig carrying this module's version, so the plugin can add the matching
// sqlx4k artifacts (driver, extensions, code generator) at consumer build time.
val generatedSourcesDir = layout.buildDirectory.dir("generated/sqlx4k/kotlin")
val generateBuildConfig = tasks.register("generateBuildConfig") {
    group = "build"
    description = "Generates BuildConfig.kt carrying this module's version."
    val version = project.version.toString()
    val outputDir = generatedSourcesDir
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/smyrgeorge/sqlx4k/gradle/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.smyrgeorge.sqlx4k.gradle

            internal object BuildConfig {
                const val VERSION: String = "$version"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildConfig)
}
