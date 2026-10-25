package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.Project

/** Resolves and adds the sqlx4k artifacts the plugin manages. */
internal object Sqlx4kDependencies {

    const val GROUP: String = "io.github.smyrgeorge"

    /**
     * The configuration of the main sources for the given [scope]: commonMain (KMP) or main (JVM),
     * e.g. `commonMainImplementation` or `api`. Fails when the project has no such configuration.
     */
    fun configurationName(project: Project, scope: DependencyScope): String {
        val kmp = project.configurations.findByName(DependencyScope.Implementation.kmpConfiguration) != null
        val name = if (kmp) scope.kmpConfiguration else scope.jvmConfiguration
        requireNotNull(project.configurations.findByName(name)) {
            val hint = if (scope == DependencyScope.Api && !kmp) {
                "apply the `java-library` plugin, or set dependencyScope = Implementation"
            } else {
                "check that the Kotlin multiplatform or jvm plugin is applied"
            }
            "sqlx4k { }: no '$name' configuration exists in this project to add the sqlx4k dependencies to — $hint."
        }
        return name
    }

    /**
     * The dependency notation of a `io.github.smyrgeorge` artifact.
     *
     * When the running build itself contains the matching project (building the sqlx4k repository
     * and its examples, or a fork), the project is used instead of the published coordinates, so
     * the build always consumes the current sources rather than a previously published artifact.
     * External builds never contain such projects and get the published coordinates.
     */
    fun notation(project: Project, name: String, version: String): Any =
        project.findProject(":$name")?.takeIf { it.group == GROUP } ?: "$GROUP:$name:$version"

    /**
     * Adds a dependency on a `io.github.smyrgeorge` artifact (see [notation]) to [configuration],
     * the implementation configuration by default.
     */
    fun add(
        project: Project,
        name: String,
        version: String,
        configuration: String = configurationName(project, DependencyScope.Implementation),
    ) {
        project.dependencies.add(configuration, notation(project, name, version))
    }
}
