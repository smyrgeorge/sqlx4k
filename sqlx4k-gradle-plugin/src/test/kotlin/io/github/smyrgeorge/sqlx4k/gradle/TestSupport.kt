package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the configuration phase to completion, firing the project's `afterEvaluate` hooks. */
internal fun Project.evaluateNow() {
    (this as ProjectInternal).evaluate()
}

/** Asserts that evaluating [project] fails with [messagePart] somewhere in the cause chain. */
internal fun assertEvaluationFails(project: Project, messagePart: String) {
    val e = assertFailsWith<Exception> { project.evaluateNow() }
    val messages = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    assertTrue(messagePart in messages, "expected '$messagePart' in:\n$messages")
}

/**
 * Applies the sqlx4k plugin to a bare project (no Kotlin plugin) and configures it. The KSP
 * configurations KSP would create on a real Kotlin project are created manually by the tests.
 */
internal fun Project.applySqlx4k(configure: (Sqlx4kExtension) -> Unit = {}): Sqlx4kExtension {
    pluginManager.apply(Sqlx4kGradlePlugin::class.java)
    val extension = extensions.getByType(Sqlx4kExtension::class.java)
    configure(extension)
    return extension
}

/** A bare project with the `implementation` and `kspCommonMainMetadata` configurations of a KMP project. */
internal fun kmpLikeProject(): Project {
    // Not `.apply { }`: on a Project that resolves to Gradle's plugin-applying `Project.apply`.
    val project = ProjectBuilder.builder().build()
    project.configurations.create("implementation")
    project.configurations.create("commonMainImplementation")
    project.configurations.create("kspCommonMainMetadata")
    return project
}
