package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.Project

/** Resolves and adds the sqlx4k artifacts the plugin manages. */
internal object Sqlx4kDependencies {

    const val GROUP: String = "io.github.smyrgeorge"

    /** The implementation configuration of the main sources: commonMain (KMP) or main (JVM). */
    fun implementationConfiguration(project: Project): String =
        if (project.configurations.findByName("commonMainImplementation") != null) "commonMainImplementation"
        else "implementation"

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

    /** Adds an implementation dependency on a `io.github.smyrgeorge` artifact (see [notation]). */
    fun add(
        project: Project,
        name: String,
        version: String,
        configuration: String = implementationConfiguration(project),
    ) {
        project.dependencies.add(configuration, notation(project, name, version))
    }
}
