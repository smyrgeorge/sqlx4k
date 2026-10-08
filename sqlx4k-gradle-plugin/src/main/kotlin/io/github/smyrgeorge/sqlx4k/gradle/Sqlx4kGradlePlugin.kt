package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The sqlx4k Gradle plugin: exposes the `sqlx4k { }` DSL (see [Sqlx4kExtension]) and wires sqlx4k
 * into the project from it.
 *
 * When applied, this plugin:
 * - Creates the `sqlx4k` DSL extension.
 * - Applies the KSP Gradle plugin and registers the sqlx4k code generator (`sqlx4k-codegen`) on the
 *   configured source sets, passing it the SQL dialect of the driver and the generated-code package
 *   (see [Sqlx4kCodegen]).
 * - Adds the generated sources of `commonMain` to the project and orders the KSP tasks accordingly.
 * - Adds the driver and the enabled extensions as dependencies, at the version this plugin was
 *   built with (unless [Sqlx4kExtension.addDependencies] is disabled).
 *
 * The Kotlin multiplatform (or jvm) plugin must be applied by the project itself.
 */
public class Sqlx4kGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("sqlx4k", Sqlx4kExtension::class.java)
        Sqlx4kCodegen.apply(target, extension)

        // Registered after the codegen's own afterEvaluate, so the options are validated first.
        target.afterEvaluate {
            if (!extension.addDependencies.get()) return@afterEvaluate
            Sqlx4kDependencies.add(target, extension.driver.get().artifact, BuildConfig.VERSION)
            extension.enabledExtensions.get().forEach { ext ->
                Sqlx4kDependencies.add(target, ext.artifact, BuildConfig.VERSION)
            }
        }
    }
}
