package io.github.smyrgeorge.sqlx4k.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Wires the sqlx4k code generator into a project: the KSP plugin, the `sqlx4k-codegen` processor
 * on the configured source sets, the KSP arguments, and the generated-sources / task-ordering
 * wiring of `commonMain`. See [Sqlx4kExtension].
 */
internal object Sqlx4kCodegen {

    internal const val CODEGEN: String = "sqlx4k-codegen"
    internal const val METADATA_KSP_TASK: String = "kspCommonMainKotlinMetadata"

    private const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
    private const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
    private const val COMMON_MAIN = "commonMain"
    private const val GENERATED_DIR = "generated/ksp/metadata/commonMain/kotlin"

    /**
     * The KSP configuration processing a source set: `commonMain` is processed by the metadata
     * compilation, `main` is the plain-JVM project case, and any other `<target>Main` source set
     * by the target's compilation. (KSP wires per-target outputs into the compilation itself;
     * only the commonMain output needs the manual source-dir and task-ordering wiring.)
     */
    internal fun kspConfigurationName(sourceSet: String): String = when {
        sourceSet == COMMON_MAIN -> "kspCommonMainMetadata"
        sourceSet == "main" -> "ksp"
        sourceSet.endsWith("Main") ->
            "ksp" + sourceSet.removeSuffix("Main").replaceFirstChar { it.uppercaseChar() }

        else -> error(
            "sqlx4k { }: unsupported source set '$sourceSet' — " +
                    "expected 'commonMain', 'main' (plain JVM) or a '<target>Main' source set."
        )
    }

    fun apply(project: Project, options: Sqlx4kExtension) {
        // The KSP wiring derives from the source sets: the first read (by KSP, or by the checks
        // below) freezes them, so a later re-assignment fails loudly instead of being ignored.
        options.sourceSets.finalizeValueOnRead()
        val kspConfigurations: Provider<Set<String>> = options.sourceSets.map { sourceSets ->
            require(sourceSets.isNotEmpty()) { "sqlx4k { }: 'sourceSets' must not be empty." }
            sourceSets.map(::kspConfigurationName).toSet()
        }

        project.pluginManager.apply(KSP_PLUGIN_ID)

        // Register the code generator on the KSP configurations of the configured source sets.
        // KSP decides whether to create a task from a configuration's declared dependencies
        // (`withDependencies` fires too late for that), so the processor is contributed as a lazy
        // element of the dependency set itself: it is realized when KSP first inspects the
        // configuration, i.e. after the build script has configured the options.
        project.configurations.configureEach { configuration ->
            if (!configuration.name.startsWith("ksp")) return@configureEach
            configuration.dependencies.addAllLater(project.provider {
                if (configuration.name in kspConfigurations.get()) {
                    listOf(project.dependencies.create(Sqlx4kDependencies.notation(project, CODEGEN, BuildConfig.VERSION)))
                } else {
                    emptyList()
                }
            })
        }

        project.pluginManager.withPlugin(KMP_PLUGIN_ID) {
            val commonMainSelected: Provider<Boolean> = options.sourceSets.map { COMMON_MAIN in it }
            // The generated sources become part of commonMain ...
            project.extensions.configure(KotlinMultiplatformExtension::class.java) { kotlin ->
                kotlin.sourceSets.named(COMMON_MAIN) { commonMain ->
                    commonMain.kotlin.srcDir(commonMainSelected.map { selected ->
                        if (selected) listOf(project.layout.buildDirectory.dir(GENERATED_DIR)) else emptyList()
                    })
                }
            }
            // ... and every compilation (the per-target compilations consume commonMain directly)
            // and every other KSP task must wait for the commonMain code generation.
            val metadataTask: Provider<List<String>> = commonMainSelected.map { selected ->
                if (selected) listOf(METADATA_KSP_TASK) else emptyList()
            }
            project.tasks.withType(KotlinCompilationTask::class.java).configureEach { it.dependsOn(metadataTask) }
            project.tasks
                .matching { it.name.startsWith("ksp") && it.name != METADATA_KSP_TASK }
                .configureEach { it.dependsOn(metadataTask) }
        }

        // The options are validated and the KSP arguments passed after the build script is fully
        // evaluated, so the whole `sqlx4k { }` block (wherever it is placed) is taken into account.
        project.afterEvaluate {
            require(options.generatedCodePackage.isPresent) { "sqlx4k { }: 'generatedCodePackage' must be set." }
            require(options.driver.isPresent) {
                "sqlx4k { }: 'driver' must be set (PostgreSQL, MySQL, MariaDB, SQLite, SQLiteCipher)."
            }
            options.enabledExtensions.get().forEach { extension ->
                val required = extension.requiredDriver ?: return@forEach
                require(options.driver.get() == required) {
                    "sqlx4k { }: the $extension extension requires the $required driver — " +
                            "the configured driver is ${options.driver.get()}."
                }
            }
            // Catch typos and layout mismatches (e.g. 'commonMain' on a plain JVM project, a target
            // that does not exist, or no Kotlin plugin at all) instead of silently generating nothing.
            kspConfigurations.get().forEach { name ->
                requireNotNull(project.configurations.findByName(name)) {
                    "sqlx4k { }: no KSP configuration '$name' exists in this project — " +
                            "check the 'sourceSets' option (${options.sourceSets.get().joinToString()}) " +
                            "and that the Kotlin multiplatform or jvm plugin is applied."
                }
            }
            project.extensions.configure(KspExtension::class.java) { ksp ->
                ksp.arg("dialect", options.driver.get().dialect)
                ksp.arg("output-package", options.generatedCodePackage.get())
                // Applied last, so a caller-supplied argument overrides the two derived above.
                options.args.get().forEach { (key, value) -> ksp.arg(key, value) }
            }
        }
    }
}
