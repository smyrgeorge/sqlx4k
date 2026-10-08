package io.github.smyrgeorge.sqlx4k.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Sqlx4kCodegenTest {

    private fun postgres(it: Sqlx4kExtension) {
        it.driver.set(Driver.PostgreSQL)
        it.generatedCodePackage.set("com.example.generated")
    }

    @Test
    fun `source sets map to the matching ksp configurations`() {
        assertEquals("kspCommonMainMetadata", Sqlx4kCodegen.kspConfigurationName("commonMain"))
        assertEquals("ksp", Sqlx4kCodegen.kspConfigurationName("main"))
        assertEquals("kspJvm", Sqlx4kCodegen.kspConfigurationName("jvmMain"))
        assertEquals("kspMacosArm64", Sqlx4kCodegen.kspConfigurationName("macosArm64Main"))
        assertFailsWith<IllegalStateException> { Sqlx4kCodegen.kspConfigurationName("weird") }
    }

    @Test
    fun `the codegen processor is registered on the configured ksp configuration only`() {
        val project = kmpLikeProject()
        // Created manually — on a real KMP project, KSP creates them.
        val metadataKsp = project.configurations.getByName("kspCommonMainMetadata")
        val jvmKsp = project.configurations.create("kspJvm")
        val jvmTestKsp = project.configurations.create("kspJvmTest")
        project.applySqlx4k(::postgres)

        val codegen = metadataKsp.dependencies.single() as ExternalDependency
        assertEquals("io.github.smyrgeorge", codegen.group)
        assertEquals("sqlx4k-codegen", codegen.name)
        assertEquals(BuildConfig.VERSION, codegen.version)
        assertTrue(jvmKsp.dependencies.isEmpty())
        assertTrue(jvmTestKsp.dependencies.isEmpty())
    }

    @Test
    fun `the configured source sets select the ksp configurations`() {
        val project = ProjectBuilder.builder().build()
        val metadataKsp = project.configurations.create("kspCommonMainMetadata")
        val jvmKsp = project.configurations.create("kspJvm")
        val macosKsp = project.configurations.create("kspMacosArm64")
        project.applySqlx4k {
            postgres(it)
            it.sourceSets.set(listOf("jvmMain", "macosArm64Main"))
        }

        assertTrue(metadataKsp.dependencies.isEmpty())
        assertEquals(1, jvmKsp.dependencies.size)
        assertEquals(1, macosKsp.dependencies.size)
    }

    @Test
    fun `the codegen processor prefers a matching in-build project`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val codegenProject = ProjectBuilder.builder().withName("sqlx4k-codegen").withParent(root).build()
        codegenProject.group = "io.github.smyrgeorge"
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val metadataKsp = app.configurations.create("kspCommonMainMetadata")
        app.applySqlx4k(::postgres)

        val codegen = metadataKsp.dependencies.single() as ProjectDependency
        assertEquals(":sqlx4k-codegen", codegen.path)
    }

    @Test
    fun `source sets are final once read`() {
        val project = kmpLikeProject()
        val extension = project.applySqlx4k(::postgres)
        // Realizing the KSP dependencies reads the source sets.
        project.configurations.getByName("kspCommonMainMetadata").dependencies.size

        assertFailsWith<IllegalStateException> { extension.sourceSets.set(listOf("jvmMain")) }
    }

    @Test
    fun `empty source sets fail the evaluation`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            postgres(it)
            it.sourceSets.set(emptyList())
        }
        assertEvaluationFails(project, "'sourceSets' must not be empty")
    }

    @Test
    fun `a missing generatedCodePackage fails the evaluation`() {
        val project = kmpLikeProject()
        project.applySqlx4k { it.driver.set(Driver.PostgreSQL) }
        assertEvaluationFails(project, "'generatedCodePackage' must be set")
    }

    @Test
    fun `a missing driver fails the evaluation`() {
        val project = kmpLikeProject()
        project.applySqlx4k { it.generatedCodePackage.set("com.example.generated") }
        assertEvaluationFails(project, "'driver' must be set")
    }

    @Test
    fun `the pgmq extension requires the postgresql driver`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            it.driver.set(Driver.SQLite)
            it.generatedCodePackage.set("com.example.generated")
            it.extensions(Extension.Pgmq)
        }
        assertEvaluationFails(project, "the Pgmq extension requires the PostgreSQL driver")
    }

    @Test
    fun `the arrow extension works with every driver`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            it.driver.set(Driver.SQLite)
            it.generatedCodePackage.set("com.example.generated")
            it.extensions(Extension.Arrow)
        }
        project.evaluateNow()
    }

    @Test
    fun `a source set without a matching ksp configuration fails the evaluation`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            postgres(it)
            it.sourceSets.set(listOf("jvmMain"))
        }
        assertEvaluationFails(project, "no KSP configuration 'kspJvm' exists")
    }

    @Test
    fun `the derived and the caller-supplied ksp arguments reach ksp, the caller's last`() {
        val project = kmpLikeProject()
        project.applySqlx4k {
            postgres(it)
            it.arg("expand-select-star", "false")
            it.arg("output-package", "com.example.override")
        }
        project.evaluateNow()

        val ksp = project.extensions.getByType(KspExtension::class.java).arguments
        assertEquals("postgresql", ksp["dialect"])
        assertEquals("false", ksp["expand-select-star"])
        // The derived `output-package` is applied first, so the caller's entry wins.
        assertEquals("com.example.override", ksp["output-package"])
    }
}
