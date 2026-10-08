package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Sqlx4kGradlePluginTest {

    private fun postgres(it: Sqlx4kExtension) {
        it.driver.set(Driver.PostgreSQL)
        it.generatedCodePackage.set("com.example.generated")
    }

    @Test
    fun `applying the plugin registers the extension and applies ksp`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(Sqlx4kGradlePlugin::class.java)

        assertNotNull(project.extensions.findByName("sqlx4k"))
        assertTrue(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
    }

    @Test
    fun `the driver dependency is added after evaluation`() {
        val project = kmpLikeProject()
        project.applySqlx4k(::postgres)

        project.evaluateNow()

        val notations = project.configurations.getByName("commonMainImplementation").dependencies
            .filterIsInstance<ExternalDependency>()
            .map { "${it.group}:${it.name}:${it.version}" }
        assertEquals(listOf("io.github.smyrgeorge:sqlx4k-postgres:${BuildConfig.VERSION}"), notations)
        // commonMainImplementation is preferred over implementation when it exists.
        assertTrue(project.configurations.getByName("implementation").dependencies.isEmpty())
    }

    @Test
    fun `every driver maps to its artifact`() {
        Driver.entries.forEach { driver ->
            val project = kmpLikeProject()
            project.applySqlx4k {
                it.driver.set(driver)
                it.generatedCodePackage.set("com.example.generated")
            }
            project.evaluateNow()

            val dependency = project.configurations.getByName("commonMainImplementation").dependencies
                .single() as ExternalDependency
            assertEquals(driver.artifact, dependency.name)
        }
    }

    @Test
    fun `enabled extensions add their artifacts`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            postgres(it)
            it.extensions(Extension.Pgmq, Extension.Arrow)
        }

        project.evaluateNow()

        val names = project.configurations.getByName("commonMainImplementation").dependencies
            .filterIsInstance<ExternalDependency>().map { it.name }
        assertEquals(listOf("sqlx4k-postgres", "sqlx4k-postgres-pgmq", "sqlx4k-arrow"), names)
    }

    @Test
    fun `addDependencies=false disables the library dependencies but keeps the codegen`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            postgres(it)
            it.extensions(Extension.Pgmq)
            it.addDependencies.set(false)
        }

        project.evaluateNow()

        assertTrue(project.configurations.getByName("commonMainImplementation").dependencies.isEmpty())
        assertTrue(project.configurations.getByName("implementation").dependencies.isEmpty())
        assertEquals(1, project.configurations.getByName("kspCommonMainMetadata").dependencies.size)
    }

    @Test
    fun `the driver dependency prefers a matching in-build project`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val driverProject = ProjectBuilder.builder().withName("sqlx4k-postgres").withParent(root).build()
        driverProject.group = "io.github.smyrgeorge"
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        app.configurations.create("implementation")
        app.configurations.create("kspCommonMainMetadata")
        app.applySqlx4k(::postgres)

        app.evaluateNow()

        val projects = app.configurations.getByName("implementation").dependencies
            .filterIsInstance<ProjectDependency>().map { it.path }
        assertEquals(listOf(":sqlx4k-postgres"), projects)
    }
}
