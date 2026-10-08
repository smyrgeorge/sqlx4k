package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sqlx4kDependenciesTest {

    @Test
    fun `implementationConfiguration prefers commonMainImplementation`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("implementation")
        assertEquals("implementation", Sqlx4kDependencies.implementationConfiguration(project))

        project.configurations.create("commonMainImplementation")
        assertEquals("commonMainImplementation", Sqlx4kDependencies.implementationConfiguration(project))
    }

    @Test
    fun `add uses the published coordinates when no matching project exists`() {
        val project = ProjectBuilder.builder().build()
        val implementation = project.configurations.create("implementation")

        Sqlx4kDependencies.add(project, "sqlx4k-postgres", "1.2.3")

        val dependency = implementation.dependencies.single() as ExternalDependency
        assertEquals("io.github.smyrgeorge", dependency.group)
        assertEquals("sqlx4k-postgres", dependency.name)
        assertEquals("1.2.3", dependency.version)
    }

    @Test
    fun `add uses the in-build project when its name and group match`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val driver = ProjectBuilder.builder().withName("sqlx4k-postgres").withParent(root).build()
        driver.group = "io.github.smyrgeorge"
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val implementation = app.configurations.create("implementation")

        Sqlx4kDependencies.add(app, "sqlx4k-postgres", "1.2.3")

        val dependency = implementation.dependencies.single() as ProjectDependency
        assertEquals(":sqlx4k-postgres", dependency.path)
    }

    @Test
    fun `add ignores an in-build project of a different group`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val other = ProjectBuilder.builder().withName("sqlx4k-postgres").withParent(root).build()
        other.group = "com.example"
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val implementation = app.configurations.create("implementation")

        Sqlx4kDependencies.add(app, "sqlx4k-postgres", "1.2.3")

        assertTrue(implementation.dependencies.single() is ExternalDependency)
    }

    @Test
    fun `add targets the given configuration`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("implementation")
        val jvmMain = project.configurations.create("jvmMainImplementation")

        Sqlx4kDependencies.add(project, "sqlx4k-postgres", "1.2.3", "jvmMainImplementation")

        assertEquals(1, jvmMain.dependencies.size)
    }
}
