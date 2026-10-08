@file:Suppress("PropertyName")

package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * The `sqlx4k { }` build-script extension: the single place to configure sqlx4k in a project.
 *
 * ```kotlin
 * sqlx4k {
 *     driver = PostgreSQL // also: MySQL, MariaDB, SQLite, SQLiteCipher
 *     generatedCodePackage = "com.example.generated"
 *     extensions(Pgmq) // sqlx4k extensions; Pgmq is PostgreSQL only
 *     // Any sqlx4k code-generator option, applied last.
 *     args = mapOf("expand-select-star" to "false")
 * }
 * ```
 *
 * Applying the plugin wires everything the code generator needs: the KSP Gradle plugin, the
 * `sqlx4k-codegen` processor on the configured [sourceSets], the `dialect` / `output-package`
 * arguments, the generated sources of `commonMain` and the ordering of the KSP tasks. Unless
 * [addDependencies] is disabled, the driver and the enabled extensions are added as well, at the
 * version this plugin was built with.
 */
public abstract class Sqlx4kExtension {
    // The Driver values, exposed directly in the DSL scope: `driver = PostgreSQL`.
    public val MySQL: Driver = Driver.MySQL
    public val MariaDB: Driver = Driver.MariaDB
    public val PostgreSQL: Driver = Driver.PostgreSQL
    public val SQLite: Driver = Driver.SQLite
    public val SQLiteCipher: Driver = Driver.SQLiteCipher

    // The parameterless extensions, exposed directly in the DSL scope: `extensions(Pgmq)`.
    public val Pgmq: Extension.Pgmq = Extension.Pgmq
    public val Arrow: Extension.Arrow = Extension.Arrow

    /**
     * The sqlx4k database driver (it also determines the SQL dialect of the code generator).
     * Required.
     */
    public abstract val driver: Property<Driver>

    /** The package the generated sources are placed in (the code generator's `output-package`). Required. */
    public abstract val generatedCodePackage: Property<String>

    /**
     * The source sets whose code the sqlx4k code generator processes. Defaults to `commonMain`
     * (generated once, visible to every target). Also supported: a target's main source set of a
     * multiplatform project (`jvmMain`, `macosArm64Main`, ...) and `main` for plain JVM projects.
     *
     * NOTE: the KSP wiring derives from it, so it is final once read (at the end of the build
     * script evaluation at the latest); a later re-assignment fails loudly.
     */
    public abstract val sourceSets: ListProperty<String>

    /**
     * The enabled sqlx4k extensions, populated via [extensions]. Defaults to none.
     * (Named to avoid Gradle's reserved `extensions` property of decorated/ExtensionAware types.)
     */
    public abstract val enabledExtensions: ListProperty<Extension>

    /**
     * Enables the given sqlx4k extensions (see [Extension]), e.g. `extensions(Pgmq)`.
     * [Pgmq] runs on PostgreSQL: enabling it with another [driver] fails the build.
     */
    public fun extensions(vararg extensions: Extension) {
        enabledExtensions.addAll(extensions.toList())
    }

    /**
     * Arguments passed to the sqlx4k KSP code generator, applied last: after the ones this plugin
     * derives itself ([driver] becomes `dialect`, [generatedCodePackage] becomes `output-package`),
     * so an entry under the same key overrides them. Defaults to none.
     *
     * This is the escape hatch for every sqlx4k code-generator option; see the sqlx4k README
     * ("Code-Generation") for the full list. For example:
     *
     * ```kotlin
     * sqlx4k {
     *     driver = PostgreSQL
     *     generatedCodePackage = "com.example.generated"
     *     args = mapOf(
     *         "validate-sql-schema" to "true",
     *         "schema-migrations-path" to "./db/migrations",
     *     )
     * }
     * ```
     *
     * NOTE: assigning replaces the whole map, [arg] adds one entry to it.
     */
    public abstract val args: MapProperty<String, String>

    /** Adds a single argument to [args]. */
    public fun arg(key: String, value: String) {
        args.put(key, value)
    }

    /**
     * Whether the sqlx4k library dependencies (the [driver] artifact and the enabled extensions)
     * are added at the version this plugin was built with. Disable to manage them (and their
     * versions) yourself. The `sqlx4k-codegen` processor is always registered. Defaults to true.
     */
    public abstract val addDependencies: Property<Boolean>

    init {
        sourceSets.convention(listOf("commonMain"))
        enabledExtensions.convention(emptyList())
        args.convention(emptyMap())
        addDependencies.convention(true)
    }
}
